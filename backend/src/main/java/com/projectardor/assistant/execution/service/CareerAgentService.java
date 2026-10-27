package com.projectardor.assistant.execution.service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.projectardor.assistant.conversation.domain.Conversation;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.AgentContextReference;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.assistant.conversation.web.AgentContextReferenceRequest;
import com.projectardor.assistant.conversation.web.AgentMessageResponse;
import com.projectardor.assistant.conversation.web.AgentConversationResponse;
import com.projectardor.assistant.memory.service.AgentMemoryView;
import com.projectardor.assistant.conversation.web.AgentStateResponse;
import com.projectardor.assistant.execution.web.AgentStreamEvent;
import com.projectardor.assistant.execution.web.AgentRunResponse;
import com.projectardor.common.security.ExternalHostResolutionException;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import tools.jackson.databind.ObjectMapper;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.memory.service.AgentMemoryService;

@Service
public class CareerAgentService {

    private static final Logger log = LoggerFactory.getLogger(CareerAgentService.class);

    private final AgentConversationStore store;
    private final AgentRunStore runStore;
    private final LangChainModelFactory modelFactory;
    private final CareerAgentTools tools;
    private final LlmConfigService llmConfigService;
    private final AgentMemoryService memoryService;
    private final AgentContextBuilder contextBuilder;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;
    private final AgentStreamProgress streamProgress;
    private final AgentToolApprovals toolApprovals;
    private final AgentPermissionGrants permissionGrants;
    private final AgentExecutionAuditStore executionAudit;
    private final AgentTraceStore traceStore;
    private final AgentModelCatalogService modelCatalog;
    private final com.projectardor.platform.usage.UsageTokenStore usageTokens;
    private final AgentMetrics agentMetrics;
    private final java.time.Duration silenceTimeout;
    private final java.time.Duration totalTimeout;
    private final ConcurrentHashMap<UUID, ActiveStream> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Semaphore> userLocks = new ConcurrentHashMap<>();
    public void validateQueuedMessage(UUID userId, com.projectardor.assistant.conversation.web.AgentMessageRequest request) {
        normalizeMessage(request.message());
        store.requireActive(userId, request.conversationId());
        resolveContexts(userId, request.contextType(), request.contextId(), request.contextReferences());
        modelCatalog.resolve(userId, request.modelChoice());
    }

    public CareerAgentService(
            AgentConversationStore store,
            AgentRunStore runStore,
            LangChainModelFactory modelFactory,
            CareerAgentTools tools,
            LlmConfigService llmConfigService,
            AgentMemoryService memoryService,
            AgentContextBuilder contextBuilder,
            ProfileService profileService,
            ObjectMapper objectMapper,
            AgentStreamProgress streamProgress, AgentToolApprovals toolApprovals,
            AgentPermissionGrants permissionGrants, AgentExecutionAuditStore executionAudit,
            AgentTraceStore traceStore, AgentModelCatalogService modelCatalog,
            com.projectardor.platform.usage.UsageTokenStore usageTokens,
            AgentMetrics agentMetrics,
            @org.springframework.beans.factory.annotation.Value("${app.agent.stream.silence-timeout:90s}") java.time.Duration silenceTimeout,
            @org.springframework.beans.factory.annotation.Value("${app.agent.stream.total-timeout:10m}") java.time.Duration totalTimeout) {
        this.store = store;
        this.runStore = runStore;
        this.modelFactory = modelFactory;
        this.tools = tools;
        this.llmConfigService = llmConfigService;
        this.memoryService = memoryService;
        this.contextBuilder = contextBuilder;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.streamProgress = streamProgress;
        this.toolApprovals = toolApprovals;
        this.permissionGrants = permissionGrants;
        this.executionAudit = executionAudit;
        this.traceStore = traceStore;
        this.modelCatalog = modelCatalog;
        this.usageTokens = usageTokens;
        this.agentMetrics = agentMetrics;
        if (silenceTimeout.isNegative() || silenceTimeout.isZero() || totalTimeout.isNegative() || totalTimeout.isZero()) throw new IllegalArgumentException("Agent 运行期限必须大于零");
        this.silenceTimeout = silenceTimeout; this.totalTimeout = totalTimeout;
    }

    public AgentStateResponse state(UUID userId, UUID requestedConversationId) {
        boolean configured = llmConfigService.get(userId).configured();
        String displayName = profileService.view(userId).displayName();
        var conversations = store.list(userId);
        var summaries = conversations.stream().map(AgentConversationResponse::from).toList();
        var archivedSummaries = store.listArchived(userId).stream()
                .map(AgentConversationResponse::from).toList();
        AgentMemoryView memory = memoryService.get(userId);
        Conversation selected;
        if (requestedConversationId != null) {
            selected = store.requireActive(userId, requestedConversationId);
        } else if (!conversations.isEmpty()) {
            selected = conversations.get(0);
        } else {
            return new AgentStateResponse(null, configured, displayName, List.of(), summaries, archivedSummaries, memory);
        }
        return new AgentStateResponse(
                selected.getId(), configured, displayName,
                conversationHistory(userId, selected.getId()), summaries, archivedSummaries, memory);
    }

    public AgentRunResponse run(UUID userId, UUID requestId) {
        return AgentRunResponse.from(runStore.get(userId, requestId));
    }

    public AgentRunResponse latestRun(UUID userId, UUID conversationId) {
        store.requireActive(userId, conversationId);
        return AgentRunResponse.from(runStore.latest(userId, conversationId)
                .orElseThrow(() -> new com.projectardor.common.web.ResourceNotFoundException("请求记录不存在")));
    }

    public AgentMessageResponse chat(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId) {
        return chat(userId, conversationId, rawMessage, contextType, contextId, List.of());
    }

    public AgentMessageResponse chat(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences) {
        return chat(userId, conversationId, rawMessage, contextType, contextId, requestedReferences, null);
    }

    public AgentMessageResponse chat(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences, UUID requestId) {
        return chat(userId, conversationId, rawMessage, contextType, contextId, requestedReferences, requestId, ReasoningDepth.AUTO);
    }

    public boolean nativeReasoningControl(UUID userId) {
        return modelFactory.reasoningProfile(userId).nativeControl();
    }

    public List<AgentConversationResponse> conversations(UUID userId) {
        return store.list(userId).stream().map(AgentConversationResponse::from).toList();
    }

    public AgentMessageResponse chat(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences, UUID requestId, ReasoningDepth requestedDepth) {
        return chatInternal(userId, conversationId, rawMessage, contextType, contextId, requestedReferences,
                requestId, requestedDepth, false, null);
    }

    public AgentMessageResponse chat(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences, UUID requestId,
            ReasoningDepth requestedDepth, String modelChoice) {
        return chatInternal(userId, conversationId, rawMessage, contextType, contextId, requestedReferences,
                requestId, requestedDepth, false, modelChoice);
    }

    /** Runs a scheduled automation: the prompt is persisted for model context but hidden from the transcript. */
    public AgentMessageResponse chatAutomation(UUID userId, UUID conversationId, String prompt) {
        String executionPrompt = """
                这是一个已经启用的定时自动化任务，现在到点执行。用户不在本轮对话中，不能向用户追问、不能要求用户确认时间或补充信息；请直接使用当前账户已有数据，调用需要的工具完成任务。
                如果某个信息确实缺失，按现有数据做最合理的处理，并在结果里说明假设，不要等待用户回复。
                自动化任务：%s
                """.formatted(prompt == null ? "" : prompt.strip());
        return chatInternal(userId, conversationId, executionPrompt, null, null, List.of(), null,
                ReasoningDepth.AUTO, true, null);
    }

    private AgentMessageResponse chatInternal(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences, UUID requestId, ReasoningDepth requestedDepth,
            boolean hiddenUserMessage) {
        return chatInternal(userId, conversationId, rawMessage, contextType, contextId, requestedReferences,
                requestId, requestedDepth, hiddenUserMessage, null);
    }

    private AgentMessageResponse chatInternal(UUID userId, UUID conversationId, String rawMessage, String contextType,
            UUID contextId, List<AgentContextReferenceRequest> requestedReferences, UUID requestId, ReasoningDepth requestedDepth,
            boolean hiddenUserMessage, String modelChoice) {
        ReasoningDepth depth = ReasoningDepth.orAuto(requestedDepth);
        String message = normalizeMessage(rawMessage);
        long startedAt = System.nanoTime();
        agentMetrics.recordRunStart();
        boolean success = false;
        if (requestId != null) MDC.put("runId", requestId.toString());
        MDC.put("userId", userId.toString());
        List<AgentContextReference> references = resolveContexts(userId, contextType, contextId, requestedReferences);
        String agentInput = contextBuilder.contextualMessage(userId, message, references);
        Semaphore lock = userLocks.computeIfAbsent(userId, ignored -> new Semaphore(1));
        if (runStore.hasEarlierQueuedMessage(userId, requestId)) throw new IllegalStateException("已有排队消息，请按顺序处理");
        if (!lock.tryAcquire()) {
            throw new IllegalStateException("Agent 正在处理上一条消息，请稍后再发送");
        }
        AgentRunTracer tracer = null;
        try {
            var runtimeConfig = modelCatalog.resolve(userId, modelChoice).config();
            Conversation conversation = conversationId == null
                    ? store.create(userId)
                    : store.requireActive(userId, conversationId);
            tracer = requestId == null ? null : AgentRunTracer.begin(traceStore, userId, requestId,
                    conversation.getId(), runtimeConfig.model(), AgentRunTracer.PROMPT_VERSION);
            UUID contextSpan = tracer == null ? null : tracer.startChild("CONTEXT", "context.load",
                    java.util.Map.of("historyLimit", 40));
            AgentContextBuilder.Prepared promptContext;
            try {
                promptContext = buildContext(userId, conversation.getId(), message, agentInput, runtimeConfig, depth, tracer, null);
            } catch (RuntimeException failure) {
                if (contextSpan != null) tracer.finishChild(contextSpan, "FAILED", contextFailureCode(failure),
                        runtimeConfig.model(), null, null, java.util.Map.of());
                throw failure;
            }
            ChatMemory memory = promptContext.memory();
            String systemMessage = promptContext.systemPrompt();
            if (contextSpan != null) {
                tracer.finishChild(contextSpan, "COMPLETED", null, runtimeConfig.model(), null, null,
                        promptContext.attributes());
            }
            CareerAgentTools.BoundCareerTools bound = tools.bind(userId, message, requestId,
                    requestId == null ? null : UUID.randomUUID());
            AgentModelTraceListener traceListener = tracer == null ? null
                    : new AgentModelTraceListener(tracer, runtimeConfig.model(), agentMetrics);
            java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners =
                    traceListener == null ? java.util.List.of() : java.util.List.of(traceListener);

            CareerAssistant assistant = AiServices.builder(CareerAssistant.class)
                    .chatModel(modelFactory.create(runtimeConfig, depth, listeners))
                    .systemMessage(systemMessage)
                    .chatMemory(memory)
                    // A synchronous retry keeps the logical request but is a new tool-call attempt.
                    // Reusing requestId as the run ID would make regenerated arguments create a second slot.
                    .tools(ApprovedAgentTools.wrap(bound, userId, null, toolApprovals, message, permissionGrants,
                            hiddenUserMessage, tracer))
                    .maxToolCallingRoundTrips(8)
                    .maxSequentialToolsInvocations(12)
                    .toolExecutionErrorHandler(new AgentToolFailureHandler())
                    .toolArgumentsErrorHandler(new AgentToolArgumentsErrorHandler())
                    .build();
            String answer = assistant.chat(agentInput);
            if (answer == null || answer.isBlank()) {
                throw new LlmCallException("AGENT_EMPTY_RESPONSE", "Agent 没有返回可用内容", true, null);
            }
            List<ConversationMessage> saved = store.appendExchange(
                    userId, conversation, message, answer.strip(),
                    bound.pendingConfirmations().isEmpty() ? null
                            : runTrace(1, List.of(), bound.pendingConfirmations()), references, hiddenUserMessage);
            if (tracer != null) tracer.complete();
            recordAgentUsage(userId, requestId, tracer, runtimeConfig.model());
            success = true;
            return AgentMessageResponse.from(saved.get(1));
        } catch (LlmCallException exception) {
            if (tracer != null) tracer.fail(exception.getCode());
            recordAgentUsage(userId, requestId, tracer, null);
            throw exception;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (tracer != null) tracer.fail("AGENT_INVALID_STATE");
            recordAgentUsage(userId, requestId, tracer, null);
            throw exception;
        } catch (RuntimeException exception) {
            Throwable root = rootCause(exception);
            log.error(
                    "Agent call failed: userId={}, conversationId={}, exceptionType={}, rootType={}, rootMessage={}",
                    userId,
                    conversationId,
                    exception.getClass().getName(),
                    root.getClass().getName(),
                    safeLogMessage(root.getMessage()));
            Throwable classified = classifyStreamFailure(exception);
            if (classified instanceof LlmCallException llmFailure) {
                if (tracer != null) tracer.fail(llmFailure.getCode());
                throw llmFailure;
            }
            boolean retryable = isTransientFailure(exception);
            if (tracer != null) tracer.fail(retryable ? "AGENT_TEMPORARILY_UNAVAILABLE" : "AGENT_LLM_FAILED");
            recordAgentUsage(userId, requestId, tracer, null);
            throw new LlmCallException(
                    retryable ? "AGENT_TEMPORARILY_UNAVAILABLE" : "AGENT_LLM_FAILED",
                    retryable
                            ? transientFailureMessage(exception)
                            : "模型调用失败。请检查模型、接口格式和 Tool Calling 兼容性；错误详情已记录",
                    retryable,
                    exception);
        } finally {
            agentMetrics.recordRunEnd(System.nanoTime() - startedAt, success, success ? null : "AGENT_CALL_FAILED");
            MDC.remove("runId");
            MDC.remove("userId");
            lock.release();
        }
    }

    public void chatStream(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId,
            StreamSink sink) {
        chatStream(userId, conversationId, rawMessage, contextType, contextId, List.of(), sink);
    }

    public void chatStream(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> requestedReferences, StreamSink sink) {
        chatStream(userId, conversationId, rawMessage, contextType, contextId, requestedReferences, null, sink);
    }

    public void chatStream(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> requestedReferences, UUID requestId, StreamSink sink) {
        chatStream(userId, conversationId, rawMessage, contextType, contextId, requestedReferences, requestId, ReasoningDepth.AUTO, sink);
    }

    public void chatStream(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> requestedReferences, UUID requestId, ReasoningDepth requestedDepth, StreamSink sink) {
        chatStream(userId, conversationId, rawMessage, contextType, contextId, requestedReferences, requestId,
                requestedDepth, null, sink);
    }

    public void chatStream(UUID userId, UUID conversationId, String rawMessage, String contextType, UUID contextId,
            List<AgentContextReferenceRequest> requestedReferences, UUID requestId, ReasoningDepth requestedDepth,
            String modelChoice, StreamSink sink) {
        ReasoningDepth depth = ReasoningDepth.orAuto(requestedDepth);
        long startedAt = System.nanoTime();
        agentMetrics.recordRunStart();
        if (requestId != null) MDC.put("runId", requestId.toString());
        MDC.put("userId", userId.toString());
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<UUID> persistedRun = new AtomicReference<>();
        AtomicReference<AgentRunTracer> traceRef = new AtomicReference<>();
        AtomicReference<AgentStreamLease> leaseRef = new AtomicReference<>();
        UUID progressId = requestId == null ? UUID.randomUUID() : requestId;
        Semaphore lock = userLocks.computeIfAbsent(userId, ignored -> new Semaphore(1));
        boolean acquired = false;
        try {
            String message = normalizeMessage(rawMessage);
            if (runStore.hasEarlierQueuedMessage(userId, requestId)) throw new IllegalStateException("已有排队消息，请按顺序处理");
            List<AgentContextReference> references = resolveContexts(userId, contextType, contextId, requestedReferences);
            String agentInput = contextBuilder.contextualMessage(userId, message, references);
            if (!lock.tryAcquire()) {
                sendStreamError(sink, new LlmCallException("AGENT_BUSY", "Agent 正在处理上一条消息，请稍后再发送", true, null), startedAt);
                return;
            }
            acquired = true;
            var runtimeConfig = modelCatalog.resolve(userId, modelChoice).config();
            Conversation conversation = conversationId == null ? store.create(userId) : store.requireActive(userId, conversationId);
            UUID logicalActionId = requestId == null ? null : runStore.logicalActionId(userId, requestId,
                    conversation.getId(), message, contextType, contextId, requestedReferences, depth);
            // Register the in-process stream before the persisted row becomes RUNNING. Otherwise a
            // cancel/steer in the begin -> activeStreams window would find no executor and could only
            // fail (500) or overwrite a RUNNING row whose owner is about to start.
            AgentStreamLease lease = new AgentStreamLease();
            leaseRef.set(lease);
            java.util.function.Predicate<String> terminate = reason -> {
                if (!finished.compareAndSet(false, true)) return false;
                lease.fence();
                AgentRunTracer interruptedTrace = traceRef.get();
                String errorCode = "USER".equals(reason) ? "AGENT_RUN_CANCELLED"
                        : "TOTAL".equals(reason) ? "AGENT_RUN_DEADLINE" : "AGENT_UPSTREAM_SILENT";
                if (interruptedTrace != null) interruptedTrace.fail(errorCode);
                recordAgentUsage(userId, requestId, interruptedTrace, runtimeConfig.model());
                agentMetrics.recordRunEnd(System.nanoTime() - startedAt, false, errorCode);
                toolApprovals.close(userId, requestId);
                streamProgress.finish(progressId, true);
                try {
                    sendStreamError(sink, new LlmCallException(errorCode,
                            "USER".equals(reason) ? "已停止本轮，后续工具不会执行。已经开始的操作可能仍在完成，请检查结果。"
                                    : "TOTAL".equals(reason) ? "已达本轮运行时限，后续工具不会执行。已开始的操作请核验结果，不会自动重试。"
                                    : "上游长时间没有新输出，本轮已停止；这不能证明模型一直在思考，也不一定是断网。不会自动重试，已开始的操作请核验结果。",
                            false, null), startedAt, userId, persistedRun.get());
                } finally {
                    MDC.remove("runId");
                    MDC.remove("userId");
                    lease.stop(() -> { activeStreams.remove(progressId); lock.release(); }); }
                return true;
            };
            activeStreams.put(progressId, new ActiveStream(userId, lease,
                    () -> terminate.test(lease.totalExpired(totalTimeout) ? "TOTAL" : "SILENCE"),
                    () -> terminate.test("USER"),
                    () -> sink.send(AgentStreamEvent.status("上游暂未返回新数据，仍在等待；这不代表模型正在思考", elapsedMs(startedAt)))));
            if (requestId != null) {
                if (finished.get()) return;
                if (!runStore.begin(userId, requestId, conversation.getId(), message,
                        contextType, contextId, requestedReferences, logicalActionId, depth, references)) {
                    if (!finished.get()) {
                        sink.send(AgentStreamEvent.error("RUN_ALREADY_STARTED",
                                "请求已被接收，请读取已有执行状态", false, elapsedMs(startedAt)));
                        sink.complete();
                    }
                    lease.stop(() -> { activeStreams.remove(progressId); lock.release(); });
                    return;
                }
                persistedRun.set(requestId);
                if (finished.get()) {
                    // The cancel won while begin() was inserting; keep the fresh row terminal.
                    runStore.fail(userId, requestId, "AGENT_RUN_CANCELLED",
                            "已停止本轮，后续工具不会执行。已经开始的操作可能仍在完成，请检查结果。", false);
                    streamProgress.finish(progressId, true);
                    toolApprovals.close(userId, requestId);
                    return;
                }
            } else if (finished.get()) {
                return;
            }
            AgentRunTracer tracer = requestId == null ? null : AgentRunTracer.begin(traceStore,
                    userId, requestId, conversation.getId(), runtimeConfig.model(), AgentRunTracer.PROMPT_VERSION);
            traceRef.set(tracer);
            streamProgress.begin(userId, progressId);
            if (finished.get()) { streamProgress.finish(progressId, true); return; }
            sink.send(AgentStreamEvent.status("正在整理对话上下文", elapsedMs(startedAt)));
            var latestContext = new java.util.concurrent.atomic.AtomicReference<AgentContextUsage>();
            java.util.function.Consumer<AgentContextUsage> contextObserver = usage -> {
                if (finished.get() || lease.stopped()) return;
                latestContext.set(usage);
                streamProgress.context(progressId, usage);
                sink.send(AgentStreamEvent.context(usage));
            };
            contextObserver.accept(AgentContextUsage.preparing(runtimeConfig.model()));
            UUID contextSpan = tracer == null ? null : tracer.startChild("CONTEXT", "context.load",
                    java.util.Map.of("historyLimit", 40));
            AgentContextBuilder.Prepared promptContext;
            try {
                promptContext = lease.modelCall(() -> buildContext(userId, conversation.getId(),
                        message, agentInput, runtimeConfig, depth, tracer, lease, contextObserver));
            } catch (RuntimeException failure) {
                if (contextSpan != null) tracer.finishChild(contextSpan, "FAILED", contextFailureCode(failure),
                        runtimeConfig.model(), null, null, java.util.Map.of());
                throw failure;
            }
            ChatMemory memory = promptContext.memory();
            String systemMessage = promptContext.systemPrompt();
            if (contextSpan != null) {
                tracer.finishChild(contextSpan, "COMPLETED", null, runtimeConfig.model(), null, null,
                        promptContext.attributes());
            }
            AgentModelTraceListener traceListener = tracer == null ? null
                    : new AgentModelTraceListener(tracer, runtimeConfig.model(), agentMetrics);
            var requestContextListener = new AgentRequestContextListener(promptContext.estimator(), latestContext.get(),
                    contextObserver, () -> !finished.get() && !lease.stopped());
            java.util.List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners =
                    traceListener == null ? java.util.List.of(requestContextListener) : java.util.List.of(traceListener, requestContextListener);
            ConcurrentHashMap<String, Long> toolStarts = new ConcurrentHashMap<>();
            toolApprovals.begin(userId, requestId);
            if (finished.get()) {
                streamProgress.finish(progressId, true);
                toolApprovals.close(userId, requestId);
                return;
            }
            List<RunStep> persistedSteps = new CopyOnWriteArrayList<>();
            AgentToolTimings toolTimings = new AgentToolTimings();
            AtomicInteger partialChunks = new AtomicInteger();
            AtomicLong firstPartialAt = new AtomicLong();
            AtomicLong lastHeartbeatAt = new AtomicLong(System.nanoTime());
            // Held rather than inlined: after the run it is asked which deletions
            // the agent proposed, so each becomes a button in the transcript.
            CareerAgentTools.BoundCareerTools bound = tools.bind(userId, message, logicalActionId, requestId)
                    .withConversationId(conversation.getId());
            StreamingCareerAssistant assistant = AiServices.builder(StreamingCareerAssistant.class)
                    .streamingChatModel(modelFactory.createStreaming(runtimeConfig, depth, lease, listeners))
                    .systemMessage(systemMessage).chatMemory(memory)
                    .tools(ApprovedAgentTools.wrap(bound, userId, requestId, toolApprovals, lease, toolTimings,
                            sink::waitingForApproval, message, permissionGrants, executionAudit,
                            conversation.getId(), false, tracer))
                    .maxToolCallingRoundTrips(8).maxSequentialToolsInvocations(12)
                    .toolExecutionErrorHandler(new AgentToolFailureHandler())
                    .toolArgumentsErrorHandler(new AgentToolArgumentsErrorHandler()).build();
            if (finished.get()) {
                if (tracer != null) tracer.fail("AGENT_RUN_CANCELLED");
                recordAgentUsage(userId, requestId, tracer, runtimeConfig.model());
                streamProgress.finish(progressId, true);
                toolApprovals.close(userId, requestId);
                return;
            }
            sink.send(AgentStreamEvent.status("等待模型输出", elapsedMs(startedAt)));
            TokenStream stream = assistant.chat(agentInput)
                    .onIntermediateResponse(response -> {
                        if (finished.get() || lease.stopped()) return;
                        String text = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
                        text = text == null ? "" : text.strip();
                        if (text.isBlank()) text = streamProgress.pendingAnswer(progressId).strip();
                        if (text.codePointCount(0, text.length()) > 1200)
                            text = text.substring(0, text.offsetByCodePoints(0, 1200)) + "…";
                        streamProgress.intermediate(progressId, text);
                        if (!text.isBlank()) persistedSteps.add(new RunStep(text, 0, "COMMENTARY"));
                        sink.send(AgentStreamEvent.intermediate(text));
                    })
                    .beforeToolExecution(before -> {
                        if (!lease.toolWaiting()) throw new java.util.concurrent.CancellationException("本轮已停止");
                        String name = before.request().name();
                        streamProgress.tool(progressId, toolLabel(name, false));
                        toolStarts.put(before.request().id(), System.nanoTime());
                        if (requestId != null) runStore.progress(userId, requestId, toolLabel(name, false), true);
                        sink.send(AgentStreamEvent.tool("tool_start", name, toolLabel(name, false), elapsedMs(startedAt)));
                    })
                    .onToolExecuted(execution -> {
                        if (!lease.toolFinished()) throw new java.util.concurrent.CancellationException("本轮已停止");
                        streamProgress.waiting(progressId);
                        Long toolStarted = toolStarts.remove(execution.request().id());
                        long duration = toolStarted == null ? 0 : Math.max(1, elapsedMs(toolStarted));
                        var timing = toolTimings.remove(execution.request().id());
                        String status = timing != null && !timing.executed() ? "NOT_EXECUTED" : execution.hasFailed() ? "FAILED" : "COMPLETED";
                        Long approvalWait = timing == null ? null : timing.approvalWaitMs();
                        Long business = timing == null ? null : timing.businessMs();
                        if (toolStarted != null) agentMetrics.recordToolCall(
                                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(duration),
                                execution.request().name(), execution.hasFailed());
                        persistedSteps.add(new RunStep(toolLabel(execution.request().name(), execution.hasFailed()), duration, status, approvalWait, business));
                        streamProgress.step(progressId, toolLabel(execution.request().name(), execution.hasFailed()), duration, status, approvalWait, business);
                        if (requestId != null) runStore.progress(userId, requestId, "正在整理回复", false);
                        sink.send(AgentStreamEvent.toolFinished(execution.request().name(),
                                toolLabel(execution.request().name(), execution.hasFailed()), duration, approvalWait, business, status));
                    })
                    .onPartialThinkingWithContext((thinking, context) -> {
                        if (!lease.attachCancellation(cancellation(context.streamingHandle()))) return;
                        if (finished.get()) return;
                        lease.data();
                        streamProgress.thinking(progressId, thinking.text());
                        sink.send(AgentStreamEvent.thinking(thinking.text()));
                    })
                    .onPartialToolCallWithContext((tool, context) -> {
                        if (lease.attachCancellation(cancellation(context.streamingHandle()))) lease.data();
                    })
                    .onPartialResponseWithContext((partial, context) -> {
                        if (!lease.attachCancellation(cancellation(context.streamingHandle()))) return;
                        if (finished.get()) return;
                        String token = partial.text();
                        if (token == null || token.isEmpty()) return;
                        lease.data();
                        streamProgress.answer(progressId, token);
                        firstPartialAt.compareAndSet(0, System.nanoTime());
                        partialChunks.incrementAndGet();
                        long now = System.nanoTime();
                        long previousHeartbeatAt = lastHeartbeatAt.get();
                        if (requestId != null && now - previousHeartbeatAt > java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                                && lastHeartbeatAt.compareAndSet(previousHeartbeatAt, now)) {
                            runStore.heartbeat(userId, requestId);
                        }
                        sink.send(AgentStreamEvent.delta(token));
                    })
                    .onCompleteResponse(response -> {
                        if (!finished.compareAndSet(false, true)) return;
                        lease.fence();
                        try {
                            String answer = response.aiMessage().text();
                            if (answer == null || answer.isBlank()) throw new IllegalStateException("Agent 没有返回可用内容");
                            long totalElapsed = elapsedMs(startedAt);
                            String trace = runTrace(totalElapsed, persistedSteps, bound.pendingConfirmations(), streamProgress.get(userId, progressId));
                            List<ConversationMessage> saved = requestId == null
                                    ? store.appendExchange(userId, conversation, message, answer.strip(), trace, references)
                                    : runStore.complete(userId, requestId, conversation, message, answer.strip(), trace, references);
                            streamProgress.finish(progressId, false);
                            if (tracer != null) tracer.complete();
                            recordAgentUsage(userId, requestId, tracer, runtimeConfig.model());
                            long firstPartialMs = firstPartialDelayMs(startedAt, firstPartialAt.get());
                            if (firstPartialMs >= 0) agentMetrics.recordTimeToFirstToken(java.time.Duration.ofMillis(firstPartialMs).toNanos());
                            agentMetrics.recordRunEnd(System.nanoTime() - startedAt, true, null);
                            log.info("Agent stream completed: userId={}, conversationId={}, partialChunks={}, firstPartialMs={}, totalMs={}",
                                    userId, conversation.getId(), partialChunks.get(), firstPartialMs, elapsedMs(startedAt));
                            bound.pendingConfirmations().forEach(
                                    pending -> sink.send(AgentStreamEvent.confirm(pending)));
                            sink.send(AgentStreamEvent.done(AgentMessageResponse.from(saved.get(1)), elapsedMs(startedAt)));
                            sink.complete();
                        } catch (RuntimeException exception) {
                            if (tracer != null) tracer.fail("AGENT_COMPLETION_FAILED");
                            recordAgentUsage(userId, requestId, tracer, runtimeConfig.model());
                            agentMetrics.recordRunEnd(System.nanoTime() - startedAt, false, "AGENT_COMPLETION_FAILED");
                            streamProgress.finish(progressId, true);
                            sendStreamError(sink, exception, startedAt, userId, persistedRun.get());
                        } finally {
                            MDC.remove("runId");
                            MDC.remove("userId");
                            toolApprovals.close(userId, requestId); lease.stop(() -> { activeStreams.remove(progressId); lock.release(); }); }
                    })
                    .onError(exception -> {
                        if (!finished.compareAndSet(false, true)) return;
                        lease.fence();
                        if (tracer != null) tracer.fail("AGENT_STREAM_FAILED");
                        recordAgentUsage(userId, requestId, tracer, runtimeConfig.model());
                        agentMetrics.recordRunEnd(System.nanoTime() - startedAt, false, "AGENT_STREAM_FAILED");
                        streamProgress.finish(progressId, true);
                        try { sendStreamError(sink, classifyStreamFailure(exception), startedAt, userId, persistedRun.get()); }
                        finally {
                            MDC.remove("runId");
                            MDC.remove("userId");
                            toolApprovals.close(userId, requestId); lease.stop(() -> { activeStreams.remove(progressId); lock.release(); }); }
                    });
            if (!finished.get()) stream.start();
        } catch (RuntimeException exception) {
            if (finished.compareAndSet(false, true)) {
                if (leaseRef.get() != null) leaseRef.get().fence();
                AgentRunTracer failedTrace = traceRef.get();
                if (failedTrace != null) failedTrace.fail("AGENT_STREAM_SETUP_FAILED");
                agentMetrics.recordRunEnd(System.nanoTime() - startedAt, false, "AGENT_STREAM_SETUP_FAILED");
                try {
                    sendStreamError(sink, exception, startedAt, userId, persistedRun.get());
                } finally {
                    MDC.remove("runId");
                    MDC.remove("userId");
                    toolApprovals.close(userId, persistedRun.get());
                    if (leaseRef.get() != null) {
                        streamProgress.finish(progressId, true);
                        leaseRef.get().stop(() -> { activeStreams.remove(progressId); lock.release(); });
                    } else if (acquired) lock.release();
                }
            }
        }
    }

    private void recordAgentUsage(UUID userId, UUID requestId, AgentRunTracer tracer, String model) {
        if (usageTokens == null || tracer == null || requestId == null) return;
        usageTokens.record(userId, com.projectardor.platform.usage.UsageFeature.AGENT_CHAT, requestId,
                (int) Math.min(Integer.MAX_VALUE, tracer.inputTokens()),
                (int) Math.min(Integer.MAX_VALUE, tracer.outputTokens()), model == null ? tracer.model() : model);
    }

    private AgentContextBuilder.Prepared buildContext(UUID userId, UUID conversationId, String message, String currentInput,
            LlmConfigService.LlmRuntimeConfig runtimeConfig, ReasoningDepth depth, AgentRunTracer tracer, AgentStreamLease lease) {
        return buildContext(userId, conversationId, message, currentInput, runtimeConfig, depth, tracer, lease, null);
    }

    private AgentContextBuilder.Prepared buildContext(UUID userId, UUID conversationId, String message, String currentInput,
            LlmConfigService.LlmRuntimeConfig runtimeConfig, ReasoningDepth depth, AgentRunTracer tracer, AgentStreamLease lease,
            java.util.function.Consumer<AgentContextUsage> contextObserver) {
        List<AgentContextBuilder.HistoryTurn> interrupted = new java.util.ArrayList<>();
        // Durable unfinished requests must survive compaction and steering.
        for (AgentMessageResponse item : conversationHistory(userId, conversationId, true)) {
            if (item.requestId() == null) continue;
            String prefix = "USER".equals(item.role()) ? "ardor:interrupted-user:" : "ardor:interrupted-status:";
            UUID synthetic = UUID.nameUUIDFromBytes((prefix + item.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (item.id().equals(synthetic)) interrupted.add(new AgentContextBuilder.HistoryTurn(
                    item.role(), item.content(), item.contextReferences(), item.createdAt()));
        }
        return contextObserver == null
                ? contextBuilder.prepare(userId, conversationId, message, currentInput, runtimeConfig, depth, tracer, lease, interrupted)
                : contextBuilder.prepare(userId, conversationId, message, currentInput, runtimeConfig, depth, tracer, lease, interrupted, contextObserver);
    }

    private List<AgentMessageResponse> conversationHistory(UUID userId, UUID conversationId) {
        return conversationHistory(userId, conversationId, false);
    }
    private List<AgentMessageResponse> conversationHistory(UUID userId, UUID conversationId, boolean recent) {
        var history = new java.util.ArrayList<>((recent ? store.recent(userId, conversationId) : store.allMessages(userId, conversationId)).stream().filter(message -> !message.isHidden()).map(AgentMessageResponse::from).toList());
        var unfinished = new java.util.ArrayList<>(runStore.unfinished(userId, conversationId, recent));
        unfinished.addAll(runStore.superseded(userId, conversationId, recent));
        for (var run : unfinished) {
            UUID userMessageId = UUID.nameUUIDFromBytes(("ardor:interrupted-user:" + run.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            UUID statusMessageId = UUID.nameUUIDFromBytes(("ardor:interrupted-status:" + run.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            history.add(new AgentMessageResponse(userMessageId, conversationId, "USER", run.message(),
                    runStore.contextReferences(userId, conversationId, run.id()), null, run.createdAt(), run.id()));
            String note = "SUPERSEDED".equals(run.errorCode()) ? "本条已由后续补充替代，未执行。" : "QUEUE_NOT_STARTED".equals(run.errorCode())
                    ? "本条未能开始：" + (run.errorMessage() == null ? "请检查引用资料和模型配置。" : run.errorMessage())
                    : "AGENT_RUN_CANCELLED".equals(run.errorCode()) ? "本轮已停止；已开始的操作请核验结果。" : "本轮未完成；不能假定操作已成功，请先核验结果。";
            AgentStreamProgress.View progress = null;
            try { progress = streamProgress.get(userId, run.id()); } catch (com.projectardor.common.web.ResourceNotFoundException absent) { /* Old runs may have no snapshot. */ }
            var steps = progress == null ? List.<RunStep>of() : progress.steps().stream()
                    .map(step -> new RunStep(step.label(), step.elapsedMs(), step.status(), step.approvalWaitMs(), step.businessMs())).toList();
            String trace = objectMapper.writeValueAsString(new RunTrace(Math.max(0, java.time.Duration.between(run.createdAt(), run.updatedAt()).toMillis()),
                    "STOPPED", steps, List.of(), progress == null ? null : progress.thinkingOutput(), progress == null ? null : progress.firstOutputMs()));
            history.add(new AgentMessageResponse(statusMessageId, conversationId, "ASSISTANT", note, List.of(), trace, run.updatedAt(), run.id()));
        }
        var turnTimes = new java.util.HashMap<UUID, java.time.Instant>();
        unfinished.forEach(run -> turnTimes.put(run.id(), run.createdAt()));
        // Keep each interrupted request and its status together before later corrections.
        // The response timestamps still retain the actual request/status times.
        history.sort(java.util.Comparator.comparing((AgentMessageResponse item) -> turnTimes.getOrDefault(item.requestId(), item.createdAt()))
                .thenComparing(item -> "USER".equals(item.role()) ? 0 : 1));
        return history;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void watchStreams() {
        activeStreams.values().forEach(active -> {
            if (active.lease().expired(silenceTimeout, totalTimeout)) active.timeout().getAsBoolean();
            else if (active.lease().warnSilence()) active.warning().run();
        });
    }

    /**
     * Explicit cancellation endpoint contract. Terminal runs are returned unchanged (idempotent),
     * a locally owned stream is fenced through the existing terminate path, and a RUNNING run
     * without a local executor becomes INTERRUPTED because this process cannot confirm that the
     * remote model or already-started tools stopped. The response always carries the run state;
     * the old endpoint no longer answers 500 for a run it cannot stop locally.
     */
    public AgentRunResponse cancelRun(UUID userId, UUID requestId) {
        AgentRunStore.AgentRun run = runStore.get(userId, requestId);
        if (!"RUNNING".equals(run.status())) return AgentRunResponse.from(run);
        ActiveStream active = activeStreams.get(requestId);
        if (active != null && active.userId().equals(userId)) {
            if (!active.cancel().getAsBoolean()) {
                // A completion/error handler already owns the terminal write; report that state
                // instead of racing it with an INTERRUPTED write.
                return AgentRunResponse.from(runStore.get(userId, requestId));
            }
            AgentRunStore.AgentRun stopped = runStore.get(userId, requestId);
            if (!"RUNNING".equals(stopped.status())) return AgentRunResponse.from(stopped);
        }
        return AgentRunResponse.from(runStore.cancel(userId, requestId, false));
    }
    private record ActiveStream(UUID userId, AgentStreamLease lease, java.util.function.BooleanSupplier timeout,
            java.util.function.BooleanSupplier cancel, Runnable warning) {}
    private Runnable cancellation(dev.langchain4j.model.chat.response.StreamingHandle handle) {
        return handle == null ? () -> {} : handle::cancel;
    }
    private static String contextFailureCode(RuntimeException failure) {
        return failure instanceof AgentContextLimitException ? "AGENT_CONTEXT_LIMIT"
                : failure instanceof java.util.concurrent.CancellationException ? "AGENT_RUN_CANCELLED" : "CONTEXT_LOAD_FAILED";
    }

    static Throwable classifyStreamFailure(Throwable failure) {
        return AgentModelFailures.classify(failure);
    }

    private void sendStreamError(StreamSink sink, Throwable exception, long startedAt) {
        sendStreamError(sink, exception, startedAt, null, null);
    }

    private void sendStreamError(StreamSink sink, Throwable exception, long startedAt, UUID userId, UUID requestId) {
        exception = classifyStreamFailure(exception);
        AgentStreamEvent event;
        if (exception instanceof LlmCallException llmException) {
            event = AgentStreamEvent.error(
                    llmException.getCode(), llmException.getMessage(), llmException.isRetryable(), elapsedMs(startedAt));
        } else if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            event = AgentStreamEvent.error(
                    exception instanceof IllegalArgumentException ? "INVALID_REQUEST" : "INVALID_STATE",
                    exception.getMessage(), false, elapsedMs(startedAt));
        } else {
            boolean retryable = isTransientFailure(exception);
            event = AgentStreamEvent.error(retryable ? "AGENT_TEMPORARILY_UNAVAILABLE" : "AGENT_LLM_FAILED",
                    retryable ? transientFailureMessage(exception) : "模型调用失败，请检查模型与 Tool Calling 兼容性",
                    retryable, elapsedMs(startedAt));
        }
        if (requestId != null) {
            try {
                AgentRunStore.AgentRun failed = runStore.fail(userId, requestId,
                        event.errorCode(), event.content(), Boolean.TRUE.equals(event.retryable()));
                event = AgentStreamEvent.error(event.errorCode(), event.content(), failed.retryable(), elapsedMs(startedAt));
            } catch (RuntimeException persistenceError) {
                log.error("Could not persist agent run failure: userId={}, requestId={}", userId, requestId, persistenceError);
                event = AgentStreamEvent.error("AGENT_RUN_STATUS_UNKNOWN",
                        "请求状态暂时无法确认，请刷新查看运行记录后再试", false, elapsedMs(startedAt));
            }
        }
        sink.send(event);
        sink.complete();
    }

    private long elapsedMs(long startedAt) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }

    static long firstPartialDelayMs(long startedAt, long firstPartialAt) {
        return firstPartialAt == 0 ? -1 : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0, firstPartialAt - startedAt));
    }

    private String runTrace(long elapsedMs, List<RunStep> toolSteps,
            List<CareerAgentTools.PendingConfirmation> pending) {
        return runTrace(elapsedMs, toolSteps, pending, null);
    }

    private String runTrace(long elapsedMs, List<RunStep> toolSteps,
            List<CareerAgentTools.PendingConfirmation> pending, AgentStreamProgress.View progress) {
        List<RunStep> steps = new java.util.ArrayList<>();
        long toolElapsed = toolSteps.stream().mapToLong(RunStep::elapsedMs).sum();
        steps.add(new RunStep("模型输出与上游等待（非纯思考时间）", Math.max(1, elapsedMs - toolElapsed), "COMPLETED"));
        steps.addAll(toolSteps);
        List<AgentStreamEvent.AgentConfirmation> proposals = pending.stream()
                .map(AgentStreamEvent.AgentConfirmation::from).toList();
        try { return objectMapper.writeValueAsString(new RunTrace(elapsedMs, "COMPLETED", steps, proposals,
                progress == null ? null : progress.thinkingOutput(), progress == null ? null : progress.firstOutputMs(),
                progress == null ? null : progress.contextUsage())); }
        catch (tools.jackson.core.JacksonException exception) {
            log.warn("Could not serialize agent run trace: {}", exception.getMessage());
            return null;
        }
    }

    private record RunTrace(long elapsedMs, String status, List<RunStep> steps,
            List<AgentStreamEvent.AgentConfirmation> proposals, String thinkingOutput, Long firstOutputMs, AgentContextUsage contextUsage) {
        RunTrace(long elapsedMs, String status, List<RunStep> steps,
                List<AgentStreamEvent.AgentConfirmation> proposals, String thinkingOutput, Long firstOutputMs) {
            this(elapsedMs, status, steps, proposals, thinkingOutput, firstOutputMs, null);
        }
    }

    public AgentStreamProgress.View progress(UUID userId, UUID requestId) {
        runStore.get(userId, requestId);
        return streamProgress.get(userId, requestId);
    }

    public List<AgentToolApprovals.View> approvals(UUID userId, UUID requestId) {
        runStore.get(userId, requestId);
        return toolApprovals.list(userId, requestId);
    }

    public void decideApproval(UUID userId, UUID requestId, UUID approvalId, boolean allow) {
        if (!"RUNNING".equals(runStore.get(userId, requestId).status())) throw new IllegalStateException("请求已结束");
        toolApprovals.decide(userId, requestId, approvalId, allow);
    }
    private record RunStep(String label, long elapsedMs, String status, Long approvalWaitMs, Long businessMs) {
        RunStep(String label, long elapsedMs, String status) { this(label, elapsedMs, status, null, null); }
    }

    private String toolLabel(String name, boolean failed) {
        String action = switch (name) {
            case "get_profile" -> "读取个人资料";
            case "list_resumes", "get_resume_analysis", "get_resume_analysis_status" -> "读取简历资料";
            case "analyze_resume" -> "提交简历分析";
            case "list_interviews", "get_next_question", "get_interview_evaluation" -> "读取模拟面试";
            case "create_interview", "submit_interview_answer", "finish_interview" -> "推进模拟面试";
            case "list_calendar_tasks" -> "读取日历";
            case "create_calendar_task" -> "添加日历待办";
            case "delete_calendar_task" -> "删除日历待办";
            case "list_recurring_tasks" -> "读取重复安排";
            case "create_recurring_task" -> "安排重复日程";
            case "delete_recurring_task" -> "取消重复安排";
            case "organize_interview_recap" -> "整理面经与薄弱点";
            case "get_interview_recap" -> "读取面经报告";
            case "list_interview_recaps" -> "读取面经";
            case "list_due_memory_cards", "list_memory_cards" -> "读取记忆卡";
            case "create_memory_card" -> "创建记忆卡";
            case "review_memory_card" -> "安排下次复习";
            case "update_user_memory" -> "更新总体记忆";
            case "list_memories" -> "读取长期记忆";
            case "add_memory" -> "新增长期记忆";
            case "delete_memory", "clear_all_memories" -> "删除长期记忆";
            case "delete_resume" -> "删除简历";
            case "delete_interview" -> "删除模拟面试";
            case "list_knowledge_documents" -> "读取知识库";
            case "search_knowledge" -> "检索知识库";
            case "research_knowledge_from_web" -> "联网扩充知识库";
            case "delete_knowledge_document" -> "删除知识文档";
            case "delete_memory_card", "delete_all_memory_cards" -> "删除记忆卡";
            case "delete_interview_recap" -> "删除面经";
            case "list_learning_plans", "get_learning_plan" -> "读取学习计划";
            case "create_learning_plan" -> "生成学习计划";
            case "update_learning_plan" -> "修改学习安排";
            case "begin_learning_plan" -> "开始学习";
            case "update_memory_card" -> "编辑记忆卡";
            case "suspend_memory_card" -> "调整记忆卡复习";
            case "update_calendar_task" -> "修改普通日程";
            case "update_recurring_task" -> "修改重复安排";
            case "update_memory" -> "修正长期记忆";
            case "cancel_interview" -> "取消模拟面试（保留记录）";
            case "list_interview_recap_jobs" -> "读取面经整理任务";
            case "delete_learning_plan" -> "提议删除学习计划";
            case "search_web" -> "联网搜索";
            default -> "调用职业工具";
        };
        return failed ? action + "失败" : action;
    }

    public AgentConversationResponse createConversation(UUID userId) {
        return AgentConversationResponse.from(store.create(userId));
    }

    private String normalizeMessage(String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
        String normalized = message.strip();
        if (normalized.length() > 50_000) throw new IllegalArgumentException("消息不能超过 50000 个字符");
        return normalized;
    }

    private List<AgentContextReference> resolveContexts(UUID userId, String legacyType, UUID legacyId,
            List<AgentContextReferenceRequest> requests) {
        java.util.LinkedHashMap<String, AgentContextReference> resolved = new java.util.LinkedHashMap<>();
        if (legacyType != null && legacyId != null) {
            AgentContextReference reference = tools.resolveContext(userId, legacyType, legacyId);
            resolved.put(reference.type() + ":" + reference.id(), reference);
        }
        if (requests != null) {
            for (AgentContextReferenceRequest request : requests) {
                if (request == null || request.type() == null || request.id() == null) {
                    throw new IllegalArgumentException("引用资料不完整");
                }
                AgentContextReference reference = tools.resolveContext(userId, request.type(), request.id());
                resolved.put(reference.type() + ":" + reference.id(), reference);
            }
        }
        if (resolved.size() > 5) throw new IllegalArgumentException("一次最多引用 5 份资料");
        return List.copyOf(resolved.values());
    }

    public AgentConversationResponse renameConversation(UUID userId, UUID conversationId, String title) {
        return AgentConversationResponse.from(store.rename(userId, conversationId, title));
    }

    public AgentConversationResponse pinConversation(UUID userId, UUID conversationId, boolean pinned) {
        return AgentConversationResponse.from(store.setPinned(userId, conversationId, pinned));
    }

    public void archiveConversation(UUID userId, UUID conversationId) {
        store.archive(userId, conversationId);
    }

    public AgentConversationResponse restoreConversation(UUID userId, UUID conversationId) {
        return AgentConversationResponse.from(store.restore(userId, conversationId));
    }

    public void deleteConversation(UUID userId, UUID conversationId) {
        store.delete(userId, conversationId);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private String safeLogMessage(String message) {
        if (message == null || message.isBlank()) return "(empty)";
        String sanitized = message.replaceAll("(?i)(api[-_ ]?key|authorization)\\s*[:=]\s*[^,\\s]+", "$1=[REDACTED]");
        return sanitized.length() <= 1200 ? sanitized : sanitized.substring(0, 1200);
    }

    private boolean isTransientFailure(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ExternalHostResolutionException) return true;
            if (current instanceof IOException) return true;
            String type = current.getClass().getSimpleName();
            if ("InternalServerException".equals(type)
                    || "RateLimitException".equals(type)
                    || "TimeoutException".equals(type)) return true;
            if ("AuthenticationException".equals(type)
                    || "AuthorizationException".equals(type)
                    || "BadRequestException".equals(type)) return false;
            if (current.getMessage() != null) messages.append(' ').append(current.getMessage());
            current = current.getCause();
        }
        String normalized = messages.toString().toLowerCase(Locale.ROOT);
        if (normalized.matches(".*\\b(400|401|403|404|405|422)\\b.*")
                || normalized.contains("invalid api key")
                || normalized.contains("unauthorized")
                || normalized.contains("authentication")) return false;
        return normalized.matches(".*\\b(408|409|425|429|500|502|503|504)\\b.*")
                || normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("connection reset")
                || normalized.contains("connection refused")
                || normalized.contains("connection closed")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("rate limit")
                || normalized.contains("overloaded");
    }

    private String transientFailureMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ExternalHostResolutionException) return "Base URL 的主机暂时无法解析";
            String type = current.getClass().getSimpleName();
            if ("RateLimitException".equals(type)) return "模型服务限流（429）";
            if ("TimeoutException".equals(type)) return "连接模型服务超时";
            if ("InternalServerException".equals(type)) return "模型服务返回临时服务器错误（5xx）";
            if (current instanceof IOException) return "模型服务网络连接中断";
            current = current.getCause();
        }
        return "模型服务连接暂时不稳定";
    }

    private interface CareerAssistant {
        String chat(String userMessage);
    }

    private interface StreamingCareerAssistant {
        TokenStream chat(String userMessage);
    }

    public interface StreamSink {
        void send(AgentStreamEvent event);
        void complete();
        default void waitingForApproval(boolean waiting) {}
    }
}

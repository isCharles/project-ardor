package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.memory.service.AgentContextCompactor;
import com.projectardor.assistant.memory.service.ConversationStructureExtractor;
import com.projectardor.assistant.memory.service.ConversationSummaryStore;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService;
import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.identity.profile.service.ProfileView;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;

/**
 * Runs the same tagged synthetic conversation through the production context
 * pipeline in three views: all messages verbatim, durable summary plus recent
 * turns, and the same summary with repeated read results compacted.
 *
 * <p>The summarizer is deterministic and extractive: it preserves every tagged
 * fact it receives. That proves the pipeline and the accounting, not the
 * semantic quality of a real provider summary. No paid model is called.</p>
 */
class ContextComparisonExperimentTests {
    private static final int SOFT_LIMIT = 12_000;
    private static final int OUTPUT_RESERVE = 1_024;
    private static final int KNOWN_WINDOW = 32_768;
    private static final int TURN_CHARS = 1_500;
    private static final int READ_RESULT_CHARS = 4_000;
    private static final long SUMMARY_LATENCY_PER_CALL_MS = 250;
    private static final List<String> TURNS = List.of("20", "80", "200");
    private static final List<String> TAGS = List.of("[[KEEP-C1]]", "[[FIX-CM1]]", "[[ENT-E1]]", "[[OPEN-U1]]");
    private static final String PROBE = "最终检查：把面试安排在周三晚上 21:00，请直接创建日程。";
    private static final Pattern TAG = Pattern.compile("\\[\\[[A-Z]+-[A-Z0-9]+]]");
    private static final List<ToolSpecification> TOOL_SPECS = List.of(
            ToolSpecification.builder().name("create_calendar_task").description("创建日程").build(),
            ToolSpecification.builder().name("list_calendar_tasks").description("读取日程").build());

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmConfigService.LlmRuntimeConfig config = new LlmConfigService.LlmRuntimeConfig(
            LlmProvider.ANTHROPIC_COMPATIBLE, "https://example.invalid", "fixture-model", "synthetic-only");
    private final TokenCountEstimator estimator = AgentRequestTokens.estimator(config);

    @Test
    void comparesRawSummaryAndRepeatedReadContextsWithoutPaidModels() throws Exception {
        var clock = new VirtualClock();
        var cases = new LinkedHashMap<String, Map<String, Run>>();
        for (String turns : TURNS) {
            int count = Integer.parseInt(turns);
            var modes = new LinkedHashMap<String, Run>();
            for (Mode mode : Mode.values()) modes.put(mode.name(), simulate(count, mode, false, true, clock));
            cases.put(turns, modes);
        }
        Run control = simulate(20, Mode.SUMMARY, true, true, clock);
        var report = buildReport(cases, control);
        Path output = Path.of("target", "context-comparison", "report.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        for (String turns : TURNS) {
            Run raw = cases.get(turns).get(Mode.RAW.name());
            Run summary = cases.get(turns).get(Mode.SUMMARY.name());
            Run repeated = cases.get(turns).get(Mode.SUMMARY_REPEAT.name());

            for (Run run : List.of(raw, summary, repeated)) {
                assertThat(run.report.missingTags()).as("%s %s retention", turns, run.report.mode()).isEmpty();
                assertThat(run.report.retainedFacts()).isEqualTo(TAGS.size());
                assertThat(run.report.violatingSideEffects()).isZero();
                assertThat(run.report.toolSpecTokens()).isEqualTo(raw.report.toolSpecTokens());
                assertThat(run.report.probe()).isEqualTo(PROBE);
                assertThat(run.report.windowKnown()).isFalse();
                assertThat(run.report.windowTokens()).isNull();
                assertThat(run.report.hardInputTokens()).isNull();
            }

            assertThat(summary.report.finalPromptTokens())
                    .as("%s turns: summary should use fewer prompt tokens than raw", turns)
                    .isLessThan(raw.report.finalPromptTokens());
            assertThat(summary.report.summaryCalls()).isPositive();
            assertThat(summary.report.summaryFailures()).isEqualTo(1);
            assertThat(summary.report.summaryLatencyMs())
                    .isEqualTo(summary.report.summaryCalls() * SUMMARY_LATENCY_PER_CALL_MS);
            assertThat(repeated.report.finalPromptTokens())
                    .as("%s turns: repeated reads should shrink the summary view", turns)
                    .isLessThan(summary.report.finalPromptTokens());
            assertThat(repeated.report.readResultsCompacted()).isEqualTo(1);
            assertThat(repeated.report.summaryCalls()).isEqualTo(summary.report.summaryCalls());
            if (!raw.report.overHardKnownWindow()) {
                // Only a raw view that fits the projected window is a fair cost baseline.
                assertThat(summary.report.totalTokens()).isLessThan(raw.report.totalTokens());
                assertThat(repeated.report.totalTokens()).isLessThan(summary.report.totalTokens());
            }
        }

        Run raw20 = cases.get("20").get(Mode.RAW.name());
        Run summary20 = cases.get("20").get(Mode.SUMMARY.name());
        Run raw80 = cases.get("80").get(Mode.RAW.name());
        Run summary80 = cases.get("80").get(Mode.SUMMARY.name());
        Run raw200 = cases.get("200").get(Mode.RAW.name());
        Run summary200 = cases.get("200").get(Mode.SUMMARY.name());
        assertThat(raw20.report.overHardKnownWindow()).isFalse();
        assertThat(raw80.report.overHardKnownWindow()).isTrue();
        assertThat(raw200.report.overHardKnownWindow()).isTrue();
        assertThat(raw200.report.overSoftBudget()).isTrue();
        assertThat(summary20.report.overHardKnownWindow()).isFalse();
        assertThat(summary80.report.overHardKnownWindow()).isFalse();
        assertThat(summary200.report.overHardKnownWindow()).isFalse();

        assertThat(control.report.missingTags()).isNotEmpty();
        assertThat(control.report.retainedFacts()).isLessThan(TAGS.size());
        assertThat(control.report.violatingSideEffects()).isEqualTo(1);
    }

    private Run simulate(int turns, Mode mode, boolean dropTags, boolean failFirst, VirtualClock clock) {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID(), promptConversation = UUID.randomUUID();
        List<ConversationMessage> history = new ArrayList<>();
        Map<UUID, List<ConversationMessage>> histories = Map.of(conversation, history, promptConversation, List.of());
        AgentConversationStore store = mock(AgentConversationStore.class);
        when(store.allMessages(any(), any())).thenAnswer(call -> histories.getOrDefault(call.getArgument(1), List.of()));

        var capacity = mock(ModelContextCapacity.class);
        when(capacity.resolve(any())).thenReturn(null);
        var policy = new AgentContextBudgetPolicy(capacity, SOFT_LIMIT, .75, .05);
        var summaries = new InMemorySummaries();
        var factory = mock(LangChainModelFactory.class);
        var summarizer = new FakeSummarizer(dropTags, failFirst ? 1 : 0, clock, estimator);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> summarizer.respond(call.getArgument(0)));
        when(factory.create(any(), any(LlmConfigService.LlmRuntimeConfig.class), any(ReasoningDepth.class), anyList()))
                .thenReturn(model);
        when(factory.maxOutputTokens(any(), any())).thenReturn(OUTPUT_RESERVE);
        var compactor = new AgentContextCompactor(store, summaries, factory, 8, SOFT_LIMIT, policy,
                null, new ConversationStructureExtractor(), null);
        var embedding = mock(MemoryEmbeddingService.class);
        when(embedding.context(any(), any())).thenReturn("");
        var profiles = mock(ProfileService.class);
        when(profiles.view(any())).thenReturn(new ProfileView("fixture", "", List.of(), "Asia/Shanghai"));
        var builder = new AgentContextBuilder(embedding, compactor, profiles, mock(CareerAgentTools.class), capacity);
        String systemPrompt = builder.prepare(user, promptConversation, "system probe", "system probe",
                config, ReasoningDepth.AUTO, null, null, List.of()).systemPrompt();

        long started = clock.millis;
        long conversationTokens = 0;
        AgentContextBuilder.Prepared prepared = null;
        for (int turn = 1; turn <= turns; turn++) {
            String content = turnContent(turn);
            history.add(ConversationMessage.user(user, conversation, content));
            if (mode != Mode.RAW) {
                prepared = builder.prepare(user, conversation, content, content, config,
                        ReasoningDepth.AUTO, null, null, List.of());
            }
            if (turn < turns) {
                conversationTokens += mode == Mode.RAW
                        ? promptTokens(systemPrompt, history.stream()
                                .map(ContextComparisonExperimentTests::toMessage).toList())
                        : promptTokens(systemPrompt, prepared.memory().messages());
                history.add(ConversationMessage.assistant(user, conversation, "已记录第 " + turn + " 轮。"));
            }
        }
        long summaryLatency = mode == Mode.RAW ? 0 : clock.millis - started;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        List<ChatMessage> body = mode == Mode.RAW
                ? history.stream().map(ContextComparisonExperimentTests::toMessage)
                        .collect(Collectors.toCollection(ArrayList::new))
                : new ArrayList<>(prepared.memory().messages());
        ChatMessage probe = body.remove(body.size() - 1);
        messages.addAll(body);
        messages.addAll(readLoop());
        messages.add(probe);
        ChatRequest request = ChatRequest.builder().messages(messages).toolSpecifications(TOOL_SPECS).build();

        int compactedReadResults = 0;
        if (mode == Mode.SUMMARY_REPEAT) {
            var budget = policy.resolve(config, OUTPUT_RESERVE);
            ChatRequest compacted = RepeatedToolResultCompactor.compact(request, budget, estimator);
            assertThat(compacted).as("repeated read results should be compacted").isNotSameAs(request);
            compactedReadResults = countCompactedReads(compacted);
            request = compacted;
        }
        int finalTokens = (int) AgentRequestTokens.estimate(request, estimator);
        conversationTokens += finalTokens;
        int summaryOverhead = summarizer.promptTokens + summarizer.outputTokens;
        int historyMessages = prepared == null ? history.size()
                : ((Number) prepared.attributes().getOrDefault("historyMessages", 0)).intValue();
        int compactedMessages = prepared == null ? 0
                : ((Number) prepared.attributes().getOrDefault("compactedMessages", 0)).intValue();
        int summaryVersion = prepared == null ? 0
                : ((Number) prepared.attributes().getOrDefault("summaryVersion", 0)).intValue();
        String text = messageText(request.messages());
        List<String> missing = TAGS.stream().filter(tag -> !text.contains(tag)).toList();
        var report = new ModeReport(mode.name(), turns, finalTokens, (int) conversationTokens,
                summaryOverhead, (int) conversationTokens + summaryOverhead,
                summarizer.calls, summarizer.retries, summarizer.failures, summaryLatency,
                TAGS.size() - missing.size(), TAGS.size(), missing,
                missing.contains("[[KEEP-C1]]") || missing.contains("[[FIX-CM1]]") ? 1 : 0,
                finalTokens >= SOFT_LIMIT, false, null, null, overHardKnownWindow(finalTokens),
                toolSpecTokens(), PROBE, compactedReadResults, historyMessages, compactedMessages, summaryVersion);
        return new Run(report, request);
    }

    private long promptTokens(String systemPrompt, List<ChatMessage> body) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(body);
        return AgentRequestTokens.estimate(ChatRequest.builder().messages(messages)
                .toolSpecifications(TOOL_SPECS).build(), estimator);
    }

    private Object buildReport(Map<String, Map<String, Run>> cases, Run control) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedBy", "ContextComparisonExperimentTests");
        report.put("model", "fixture-model (synthetic, no provider call)");
        report.put("summarizer", "deterministic tag-preserving fixture; cannot prove real model semantics");
        report.put("estimator", "AgentRequestTokens.estimator -> approximate char/2");
        report.put("softInputTokens", SOFT_LIMIT);
        report.put("windowKnown", false);
        report.put("hardInputTokens", null);
        report.put("knownWindowProjectionTokens", KNOWN_WINDOW);
        report.put("claims", List.of(
                "pipeline and token accounting are recomputable without paid models",
                "no real-model quality, losslessness or historical improvement is claimed",
                "modes over the projected known window are listed separately and excluded from savings conclusions"));
        Map<String, Object> caseMap = new LinkedHashMap<>();
        cases.forEach((turns, modes) -> {
            Map<String, Object> modeMap = new LinkedHashMap<>();
            modes.forEach((mode, run) -> modeMap.put(mode, run.report));
            caseMap.put(turns, modeMap);
        });
        report.put("cases", caseMap);
        report.put("lossyControl", control.report);
        return report;
    }

    private Boolean overHardKnownWindow(int tokens) {
        var capacity = mock(ModelContextCapacity.class);
        when(capacity.resolve(any())).thenReturn((long) KNOWN_WINDOW);
        var budget = new AgentContextBudgetPolicy(capacity, SOFT_LIMIT, .75, .05)
                .resolve(config, OUTPUT_RESERVE);
        return budget.exceedsHardLimit(tokens);
    }

    private int toolSpecTokens() {
        return (int) AgentRequestTokens.estimate(ChatRequest.builder()
                .messages(List.of(SystemMessage.from("tool spec measurement")))
                .toolSpecifications(TOOL_SPECS).build(), estimator);
    }

    private static int countCompactedReads(ChatRequest request) {
        return (int) request.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(message -> message.text() != null && message.text().contains("重复的只读工具结果"))
                .count();
    }

    private static List<ChatMessage> readLoop() {
        String output = "{\"tasks\":\"" + "synthetic calendar row;".repeat(READ_RESULT_CHARS / 22) + "\"}";
        List<ChatMessage> messages = new ArrayList<>();
        for (int call = 1; call <= 3; call++) {
            String id = "call-read-" + call;
            messages.add(AiMessage.from(ToolExecutionRequest.builder().id(id)
                    .name("list_calendar_tasks").arguments("{}").build()));
            messages.add(ToolExecutionResultMessage.builder().id(id).toolName("list_calendar_tasks")
                    .text(output).attributes(Map.of("source", "fixture")).build());
        }
        return messages;
    }

    private static String turnContent(int turn) {
        String tag = switch (turn) {
            case 1 -> "[[KEEP-C1]] 硬约束：周一到周五晚上 20:00 以后不安排任何面试或准备任务。";
            case 2 -> "[[FIX-CM1]] 更正：周三晚上也不行，面试只能安排在周末上午。";
            case 3 -> "[[ENT-E1]] 目标岗位实体 3f2b8c1e-9d4a-4f6b-8c2d-1a2b3c4d5e6f（字节跳动后端实习）。";
            case 4 -> "[[OPEN-U1]] 尚未决定：是否接受上海出差。";
            default -> "";
        };
        String filler = ("第 " + turn + " 轮：继续整理求职计划、简历反馈和面试准备。").repeat(80);
        return (tag + " " + filler).substring(0, Math.min(TURN_CHARS, tag.length() + filler.length()));
    }

    private static ChatMessage toMessage(ConversationMessage message) {
        return "ASSISTANT".equals(message.getRole())
                ? AiMessage.from(message.getContent()) : UserMessage.from(message.getContent());
    }

    private static String messageText(List<ChatMessage> messages) {
        return messages.stream().map(Object::toString).collect(Collectors.joining("\n"));
    }

    private enum Mode { RAW, SUMMARY, SUMMARY_REPEAT }

    private record Run(ModeReport report, ChatRequest request) {}

    record ModeReport(String mode, int turns, int finalPromptTokens, int conversationTokens,
            int summaryOverheadTokens, int totalTokens, int summaryCalls, int summaryRetries,
            int summaryFailures, long summaryLatencyMs, int retainedFacts, int totalFacts,
            List<String> missingTags, int violatingSideEffects, boolean overSoftBudget,
            boolean windowKnown, Long windowTokens, Long hardInputTokens, Boolean overHardKnownWindow,
            int toolSpecTokens, String probe, int readResultsCompacted,
            int historyMessages, int compactedMessages, int summaryVersion) {}

    private static final class VirtualClock {
        long millis;
        void advance(long amount) { millis += amount; }
    }

    private static final class FakeSummarizer {
        private final boolean dropTags;
        private final int failuresAllowed;
        private final VirtualClock clock;
        private final TokenCountEstimator estimator;
        int calls;
        int failures;
        int retries;
        int promptTokens;
        int outputTokens;
        boolean failed;

        FakeSummarizer(boolean dropTags, int failuresAllowed, VirtualClock clock, TokenCountEstimator estimator) {
            this.dropTags = dropTags;
            this.failuresAllowed = failuresAllowed;
            this.clock = clock;
            this.estimator = estimator;
        }

        ChatResponse respond(ChatRequest request) {
            calls++;
            clock.advance(SUMMARY_LATENCY_PER_CALL_MS);
            promptTokens += AgentRequestTokens.estimate(request, estimator);
            if (calls <= failuresAllowed) {
                failures++;
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("truncated fixture output"))
                        .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.LENGTH).build())
                        .build();
            }
            if (failures > 0 && !failed) {
                retries++;
                failed = true;
            }
            String text = summaryJson(request.messages().toString(), dropTags);
            outputTokens += estimator.estimateTokenCountInText(text);
            return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
        }

        private String summaryJson(String prompt, boolean dropTags) {
            List<String> tags = dropTags ? List.of() : tagsIn(prompt);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("goals", tags.stream().filter(tag -> tag.startsWith("[[KEEP-")).toList());
            summary.put("decisions", List.of());
            summary.put("corrections", tags.stream().filter(tag -> tag.startsWith("[[FIX-")).toList());
            summary.put("entities", tags.stream().filter(tag -> tag.startsWith("[[ENT-"))
                    .map(tag -> Map.of("type", "fixture", "id", tag, "description", "保留的实体"))
                    .toList());
            summary.put("unresolved", tags.stream().filter(tag -> tag.startsWith("[[OPEN-")).toList());
            summary.put("toolConclusions", List.of());
            summary.put("narrative", tags.isEmpty() ? "摘要省略了全部细节标签" : "保留事实：" + String.join(" ", tags));
            try {
                return new ObjectMapper().writeValueAsString(summary);
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private static List<String> tagsIn(String prompt) {
            Matcher matcher = TAG.matcher(prompt);
            var tags = new LinkedHashSet<String>();
            while (matcher.find()) tags.add(matcher.group());
            return List.copyOf(tags);
        }
    }

    private static final class InMemorySummaries extends ConversationSummaryStore {
        private Summary latest;

        InMemorySummaries() { super(null); }

        @Override
        public Optional<Summary> latest(UUID userId, UUID conversationId) {
            return Optional.ofNullable(latest);
        }

        @Override
        public Summary save(UUID userId, UUID conversationId, int version, UUID coveredMessageId,
                int coveredCount, String content, int tokenCount, String model) {
            latest = new Summary(UUID.randomUUID(), version, coveredMessageId, coveredCount,
                    content, tokenCount, model, Instant.now());
            return latest;
        }
    }
}

package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.domain.*;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.execution.web.AgentStreamEvent;
import com.projectardor.assistant.memory.service.*;
import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import tools.jackson.databind.json.JsonMapper;

/** The actual streaming AI Services entry, including former keyword-triggered requests. */
class CareerAgentRuntimeIntegrationTests {
    @ParameterizedTest @ValueSource(strings = {"请只读分析我的准备情况", "read-only audit my preparation", "请分析我的准备情况"})
    void keywordsUseTheSameStreamingEntryWithoutInventedPhases(String message) {
        var f = new Fixture(); f.run(message);
        assertThat(f.requests).hasSize(1);
        assertThat(f.events).anyMatch(event -> "delta".equals(event.type()));
        assertThat(f.events.stream().filter(event -> "done".equals(event.type()))).hasSize(1);
        assertThat(f.events).noneMatch(event -> "phase".equals(event.type()) || "phase_end".equals(event.type()));
        verify(f.factory, never()).create(any(LlmConfigService.LlmRuntimeConfig.class), any(ReasoningDepth.class), anyList());
    }

    @Test void generatedSummaryIsDataAndHistorySurvivesConsecutiveRuns() {
        var f = new Fixture(); f.summary = "PAST-DATA-ONLY";
        f.run("请只读分析"); f.run("继续分析");
        assertThat(f.requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.messages().stream().filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                    .map(Object::toString)).allSatisfy(text -> assertThat(text).doesNotContain("PAST-DATA-ONLY"));
            assertThat(request.messages().stream().filter(message -> message instanceof AiMessage).map(Object::toString))
                    .anySatisfy(text -> assertThat(text).contains("PAST-DATA-ONLY"));
            assertThat(request.messages().toString()).contains("Earlier user instruction");
        });
        assertThat(f.events.stream().filter(event -> "done".equals(event.type()))).hasSize(2);
    }

    @Test void ordinaryToolLoopPreservesReadsWithoutTheOldThreeToolGraphLimit() {
        var f = new Fixture();
        f.reply = (request, handler) -> {
            if (request.messages().stream().noneMatch(message -> message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)) {
                var calls = java.util.stream.IntStream.range(0, 4).mapToObj(index ->
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("read-" + index).name("list_resumes").arguments("{}").build()).toList();
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(calls)).build());
            } else handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Synthetic final answer")).build());
        };
        f.run("请只读分析准备情况");
        verify(f.resumeReads, times(4)).listResumes();
        assertThat(f.requests).hasSize(2);
        assertThat(f.events.stream().filter(event -> "done".equals(event.type()))).hasSize(1);
    }

    @Test void failedUserInstructionsRemainAfterCompaction() {
        var f = new Fixture();
        var old = new AgentRunStore.AgentRun(UUID.randomUUID(), f.conversation.getId(), "Delete 1 2 3 and regenerate",
                "FAILED", "Stopped", "AGENT_RUN_CANCELLED", "stopped", false, false,
                java.time.Instant.now().minusSeconds(20), java.time.Instant.now().minusSeconds(10));
        when(f.runs.unfinished(f.user, f.conversation.getId(), true)).thenReturn(List.of(old));
        when(f.progress.get(f.user, old.id())).thenThrow(new com.projectardor.common.web.ResourceNotFoundException("no snapshot"));
        f.run("等等，2不要重新生成");
        assertThat(f.requests).hasSize(1).allSatisfy(request -> assertThat(request.messages().toString())
                .contains("Delete 1 2 3 and regenerate", "本轮已停止", "等等，2不要重新生成"));
    }

    @org.junit.jupiter.api.RepeatedTest(10) void cancellationDuringCompactionCannotDispatchTheModel() throws Exception {
        var f = new Fixture(Duration.ofMillis(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(f.compactor.prepare(any(), any(), anyString(), any(), any(), anyList(), any(), anyString())).thenAnswer(call -> {
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            throw new java.util.concurrent.CancellationException("stopped context");
        });
        var execution = java.util.concurrent.CompletableFuture.runAsync(() -> f.run("请只读分析"));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        expire(f, execution); execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(f.requests).isEmpty();
        assertThat(f.events).anyMatch(event -> "AGENT_RUN_DEADLINE".equals(event.errorCode()));
    }

    @org.junit.jupiter.api.RepeatedTest(10) void deadlineRejectsLateCompletion() throws Exception {
        var f = new Fixture(Duration.ofMillis(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var returned = new java.util.concurrent.CountDownLatch(1);
        f.reply = (request, handler) -> {
            Thread.ofVirtual().start(() -> {
                entered.countDown();
                try {
                    release.await();
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("late answer")).build());
                } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                finally { returned.countDown(); }
            });
        };
        try {
            f.run("请只读分析");
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100 && f.events.stream().noneMatch(event -> "AGENT_RUN_DEADLINE".equals(event.errorCode())); i++) {
                f.service.watchStreams(); Thread.sleep(5);
            }
            assertThat(f.requests).hasSize(1);
            assertThat(f.events).anyMatch(event -> "AGENT_RUN_DEADLINE".equals(event.errorCode()));
        } finally { release.countDown(); }
        assertThat(returned.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(f.events).noneMatch(event -> "done".equals(event.type()));
    }

    @Test void persistenceFailureReportsErrorAndReleasesLock() {
        var f = new Fixture();
        when(f.conversations.appendExchange(any(), any(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("synthetic save failure"));
        f.run("请只读分析"); f.run("继续");
        assertThat(f.events.stream().filter(event -> "error".equals(event.type()))).hasSize(2);
        assertThat(f.events).noneMatch(event -> "AGENT_BUSY".equals(event.errorCode()) || "done".equals(event.type()));
    }

    private static void expire(Fixture f, java.util.concurrent.CompletableFuture<Void> execution) throws Exception {
        for (int i = 0; i < 100 && !execution.isDone(); i++) {
            f.service.watchStreams(); if (!execution.isDone()) Thread.sleep(5);
        }
    }
    public static class ResumeReads {
        @dev.langchain4j.agent.tool.Tool(name = "list_resumes", value = "Synthetic module read")
        public List<String> listResumes() { return List.of(); }
    }
    private static final class Fixture {
        final UUID user = UUID.randomUUID();
        final Conversation conversation = Conversation.create(user, "Synthetic runtime");
        final AgentConversationStore conversations = mock(AgentConversationStore.class);
        final AgentRunStore runs = mock(AgentRunStore.class);
        final AgentStreamProgress progress = mock(AgentStreamProgress.class);
        final AgentContextCompactor compactor = mock(AgentContextCompactor.class);
        final LangChainModelFactory factory = mock(LangChainModelFactory.class);
        final ResumeReads resumeReads = mock(ResumeReads.class);
        final List<AgentStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ChatRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        String summary = "";
        java.util.function.BiConsumer<ChatRequest, StreamingChatResponseHandler> reply = (request, handler) -> {
            handler.onPartialResponse("Synthetic final answer");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Synthetic final answer")).build());
        };
        final CareerAgentService service;
        Fixture() { this(Duration.ofMinutes(10)); }
        Fixture(Duration timeout) {
            var config = new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "fixture-model", "synthetic-only");
            var catalog = mock(AgentModelCatalogService.class);
            when(catalog.resolve(eq(user), any())).thenReturn(new AgentModelCatalogService.Resolved("PRIMARY", config));
            when(conversations.requireActive(user, conversation.getId())).thenReturn(conversation);
            when(conversations.appendExchange(any(), any(), anyString(), anyString(), anyString(), anyList())).thenAnswer(call -> List.of(
                    ConversationMessage.user(user, conversation.getId(), call.getArgument(2)), ConversationMessage.assistant(user, conversation.getId(), call.getArgument(3))));
            var tools = mock(CareerAgentTools.class);
            var bound = mock(CareerAgentTools.BoundCareerTools.class, RETURNS_SELF);
            when(tools.bind(eq(user), anyString(), any(), any())).thenReturn(bound);
            when(bound.toolObjects()).thenReturn(List.of(bound, resumeReads));
            var embedding = mock(MemoryEmbeddingService.class); when(embedding.context(eq(user), anyString())).thenReturn("");
            var profiles = mock(ProfileService.class);
            when(profiles.view(user)).thenReturn(com.projectardor.platform.identity.profile.service.ProfileView.from(UserProfile.create(user, "Synthetic owner")));
            when(compactor.prepare(eq(user), eq(conversation.getId()), anyString(), eq(config), any(), anyList(), any(), anyString())).thenAnswer(call ->
                    new AgentContextCompactor.Prepared(call.getArgument(2), List.of(ConversationMessage.user(user, conversation.getId(), "Earlier user instruction")), 0, 0, 10, null, summary));
            StreamingChatModel model = new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { requests.add(request); reply.accept(request, handler); }
            };
            when(factory.createStreaming(eq(config), any(ReasoningDepth.class), any(), anyList())).thenReturn(model);
            var approvals = mock(AgentToolApprovals.class); when(approvals.canRead(eq(user), any())).thenReturn(true);
            service = new CareerAgentService(conversations, runs, factory, tools, mock(LlmConfigService.class), mock(AgentMemoryService.class),
                    new AgentContextBuilder(embedding, compactor, profiles, tools), profiles, JsonMapper.builder().build(), progress, approvals,
                    mock(AgentPermissionGrants.class), mock(AgentExecutionAuditStore.class), mock(AgentTraceStore.class), catalog,
                    mock(com.projectardor.platform.usage.UsageTokenStore.class), Duration.ofSeconds(90), timeout);
        }
        void run(String message) { service.chatStream(user, conversation.getId(), message, null, null, new CareerAgentService.StreamSink() {
            public void send(AgentStreamEvent event) { events.add(event); } public void complete() {}
        }); }
    }
}

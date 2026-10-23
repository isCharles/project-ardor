package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the production graph branch, not only the standalone state machine. No paid model. */
class CareerAgentGraphIntegrationTests {
    @Test void generatedSummaryNeverEntersTheSystemInstruction() {
        var fixture = new Fixture();
        when(fixture.compactor.prepare(any(), any(), anyString(), any(), any(), anyList(), any())).thenReturn(
                new AgentContextCompactor.Prepared("Trusted system", List.of(), 1, 4, 20, null, "PAST-DATA-ONLY"));
        fixture.run();
        assertThat(fixture.requests).hasSize(4).allSatisfy(request -> {
            assertThat(request.messages().stream().filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                    .map(Object::toString).toList()).allSatisfy(text -> assertThat(text).doesNotContain("PAST-DATA-ONLY"));
            assertThat(request.messages().stream().filter(message -> message instanceof AiMessage).map(Object::toString).toList())
                    .anySatisfy(text -> assertThat(text).contains("PAST-DATA-ONLY"));
        });
    }

    @org.junit.jupiter.api.RepeatedTest(20) void cancellationDuringContextCompactionCannotDispatchTheGraph() throws Exception {
        var fixture = new Fixture(Duration.ofMillis(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(fixture.compactor.prepare(any(), any(), anyString(), any(), any(), anyList(), any())).thenAnswer(call -> {
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            throw new java.util.concurrent.CancellationException("stopped context");
        });
        var execution = java.util.concurrent.CompletableFuture.runAsync(fixture::run);
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        expire(fixture, execution);
        execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(fixture.requests).isEmpty();
        assertThat(fixture.events).anySatisfy(event -> assertThat(event.errorCode()).isEqualTo("AGENT_RUN_DEADLINE"));
    }

    @Test void gatherCannotExceedThreeActualToolExecutionsEvenIfTheModelRequestsFour() {
        var fixture = new Fixture();
        when(fixture.model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            ChatRequest request = call.getArgument(0);
            fixture.requests.add(request);
            String prompt = request.messages().toString();
            if (prompt.contains("只读检索阶段") && request.messages().stream()
                    .noneMatch(message -> message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)) {
                var calls = java.util.stream.IntStream.range(0, 4).mapToObj(index ->
                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("fixture-" + index)
                                .name("list_resumes").arguments("{}").build()).toList();
                return ChatResponse.builder().aiMessage(AiMessage.from(calls)).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(prompt.contains("证据审查器")
                    ? "SUFFICIENT" : "Synthetic phase result")).build();
        });
        fixture.run();
        verify(fixture.resumeReads, times(3)).listResumes();
        assertThat(fixture.events.stream().filter(event -> event.type().equals("done"))).hasSize(1);
    }

    @org.junit.jupiter.api.RepeatedTest(20) void deadlineStopsTheRunningPhaseWithoutDispatchingGatherOrAnswer() throws Exception {
        var fixture = new Fixture(Duration.ofMillis(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        when(fixture.model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            fixture.requests.add(call.getArgument(0));
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            return ChatResponse.builder().aiMessage(AiMessage.from("late plan")).build();
        });
        var execution = java.util.concurrent.CompletableFuture.runAsync(fixture::run);
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        expire(fixture, execution);
        execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(fixture.requests).hasSize(1);
        assertThat(fixture.events).anySatisfy(event -> assertThat(event.errorCode()).isEqualTo("AGENT_RUN_DEADLINE"));
        assertThat(fixture.events.stream().filter(event -> event.type().equals("done"))).isEmpty();
    }

    @Test void completedGraphReleasesTheUserLockAndCarriesHistoryIntoEveryPhase() {
        var fixture = new Fixture();
        fixture.run();
        fixture.run();
        assertThat(fixture.events.stream().filter(event -> event.type().equals("done"))).hasSize(2);
        assertThat(fixture.events.stream().filter(event -> event.type().equals("error"))).isEmpty();
        assertThat(fixture.requests).hasSize(8).allSatisfy(request ->
                assertThat(request.messages().toString()).contains("Earlier user instruction"));
    }

    @Test void persistenceFailureProducesAnErrorAndDoesNotLeakTheUserLock() {
        var fixture = new Fixture();
        when(fixture.conversations.appendExchange(any(), any(), anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("synthetic save failure"));
        fixture.run();
        fixture.run();
        assertThat(fixture.events.stream().filter(event -> event.type().equals("error"))).hasSize(2);
        assertThat(fixture.events).noneSatisfy(event -> assertThat(event.errorCode()).isEqualTo("AGENT_BUSY"));
        assertThat(fixture.events.stream().filter(event -> event.type().equals("done"))).isEmpty();
    }

    @Test void failedUserTurnsRemainInTheGraphContextAfterCompaction() {
        var fixture = new Fixture();
        var oldRun = new AgentRunStore.AgentRun(UUID.randomUUID(), fixture.conversation.getId(),
                "Delete 1 2 3 and regenerate", "FAILED", "Stopped", "AGENT_RUN_CANCELLED", "stopped", false, false,
                java.time.Instant.now().minusSeconds(20), java.time.Instant.now().minusSeconds(10));
        when(fixture.runs.unfinished(fixture.user, fixture.conversation.getId(), true)).thenReturn(List.of(oldRun));
        when(fixture.progress.get(fixture.user, oldRun.id())).thenThrow(new com.projectardor.common.web.ResourceNotFoundException("no snapshot"));
        fixture.run();
        assertThat(fixture.events.stream().filter(event -> event.type().equals("done"))).hasSize(1);
        assertThat(fixture.requests).allSatisfy(request ->
                assertThat(request.messages().toString()).contains("Delete 1 2 3 and regenerate", "本轮已停止"));
    }

    private static void expire(Fixture fixture, java.util.concurrent.CompletableFuture<Void> execution) throws Exception {
        // Fast CI can reach the latch before the real 1 ms deadline. Observe the
        // actual expired state instead of assuming one immediate watch already expired.
        for (int attempt = 0; attempt < 100 && !execution.isDone(); attempt++) {
            fixture.service.watchStreams();
            if (!execution.isDone()) Thread.sleep(5);
        }
    }

    public static class ResumeReads {
        @dev.langchain4j.agent.tool.Tool(name = "list_resumes", value = "Synthetic module read")
        public List<String> listResumes() { return List.of(); }
    }

    private static final class Fixture {
        final UUID user = UUID.randomUUID();
        final Conversation conversation = Conversation.create(user, "Synthetic graph");
        final AgentConversationStore conversations = mock(AgentConversationStore.class);
        final AgentRunStore runs = mock(AgentRunStore.class);
        final AgentStreamProgress progress = mock(AgentStreamProgress.class);
        final AgentContextCompactor compactor = mock(AgentContextCompactor.class);
        final CareerAgentTools.BoundCareerTools bound = mock(CareerAgentTools.BoundCareerTools.class, RETURNS_SELF);
        final ResumeReads resumeReads = mock(ResumeReads.class);
        final List<AgentStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ChatRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ChatModel model = mock(ChatModel.class);
        final CareerAgentService service;

        Fixture() { this(Duration.ofMinutes(10)); }
        Fixture(Duration totalTimeout) {
            var config = new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE,
                    "https://example.invalid", "fixture-model", "synthetic-only");
            var catalog = mock(AgentModelCatalogService.class);
            when(catalog.resolve(eq(user), any())).thenReturn(new AgentModelCatalogService.Resolved("PRIMARY", config));
            when(conversations.requireActive(user, conversation.getId())).thenReturn(conversation);
            when(conversations.appendExchange(any(), any(), anyString(), anyString(), anyString(), anyList()))
                    .thenAnswer(call -> List.of(ConversationMessage.user(user, conversation.getId(), call.getArgument(2)),
                            ConversationMessage.assistant(user, conversation.getId(), call.getArgument(3))));
            var tools = mock(CareerAgentTools.class);
            when(tools.bind(eq(user), anyString(), any(), any())).thenReturn(bound);
            when(bound.toolObjects()).thenReturn(List.of(bound, resumeReads));
            var memoryEmbedding = mock(MemoryEmbeddingService.class);
            when(memoryEmbedding.context(eq(user), anyString())).thenReturn("");
            var profiles = mock(ProfileService.class);
            when(profiles.get(user)).thenReturn(UserProfile.create(user, "Synthetic owner"));
            when(compactor.prepare(eq(user), eq(conversation.getId()), anyString(), eq(config), any(), anyList(), any()))
                    .thenReturn(new AgentContextCompactor.Prepared("Synthetic system", List.of(
                            ConversationMessage.user(user, conversation.getId(), "Earlier user instruction")), 0, 0, 10, null));
            when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
                ChatRequest request = call.getArgument(0);
                requests.add(request);
                String prompt = request.messages().toString();
                String text = prompt.contains("证据审查器") ? "SUFFICIENT"
                        : prompt.contains("最终报告生成器") ? "Synthetic final answer" : "Synthetic phase result";
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            });
            var factory = mock(LangChainModelFactory.class);
            when(factory.create(eq(config), any(ReasoningDepth.class), anyList())).thenReturn(model);
            service = new CareerAgentService(conversations, runs, factory, tools, mock(LlmConfigService.class),
                    mock(AgentMemoryService.class), memoryEmbedding, compactor, profiles,
                    JsonMapper.builder().build(), progress, approvals(),
                    mock(AgentPermissionGrants.class), mock(AgentExecutionAuditStore.class), mock(AgentTraceStore.class),
                    catalog, mock(com.projectardor.platform.usage.UsageTokenStore.class), Duration.ofSeconds(90), totalTimeout);
        }

        private AgentToolApprovals approvals() {
            var approvals = mock(AgentToolApprovals.class);
            when(approvals.canRead(eq(user), any())).thenReturn(true);
            return approvals;
        }

        void run() {
            service.chatStream(user, conversation.getId(), "请只读分析我的准备情况", null, null, new CareerAgentService.StreamSink() {
                public void send(AgentStreamEvent event) { events.add(event); }
                public void complete() {}
            });
        }
    }
}

package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.RuntimeContextGovernor.Reduction;
import com.projectardor.assistant.execution.service.RuntimeContextGovernor.Settings;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

class RuntimeContextGovernorTests {

    private final List<RuntimeContextGovernor.Reduction> reported = new ArrayList<>();
    private final List<String> states = new ArrayList<>();

    private final RuntimeContextGovernor.Observer observer = new RuntimeContextGovernor.Observer() {
        @Override public void onState(String state) { states.add(state); }

        @Override public void onReduction(Reduction reduction) { reported.add(reduction); }
    };

    /** Small enough to make the ladder's arithmetic readable: soft 400, hard 1000, 150 tokens of slack. */
    private static final AgentContextBudgetPolicy.Budget BUDGET =
            new AgentContextBudgetPolicy.Budget(4000L, 400, 1000L, 100, 50);

    /**
     * The multi-round fixtures are measured, not imagined: a 4,000-character result costs ~2,000 tokens
     * and its folded line ~160, so three folds of a four-round loop land around 1,440 tokens. This budget
     * is what makes that run reduce and still fit, and it is why the ceiling is set per fixture.
     */
    private static final AgentContextBudgetPolicy.Budget LOOP_BUDGET =
            new AgentContextBudgetPolicy.Budget(8000L, 1200, 2000L, 100, 50);

    private RuntimeContextGovernor governor(RuntimeContextGovernor.RunTextSummarizer summarizer,
            java.util.function.BooleanSupplier active, Settings settings) {
        return governor(summarizer, active, settings, BUDGET);
    }

    private RuntimeContextGovernor governor(RuntimeContextGovernor.RunTextSummarizer summarizer,
            java.util.function.BooleanSupplier active, Settings settings, AgentContextBudgetPolicy.Budget budget) {
        return new RuntimeContextGovernor(budget, null, settings, summarizer, active, observer);
    }

    @Test void requestsBelowTheSoftThresholdAreSentWithoutTouchingTheLadder() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("fine"));
        var request = ChatRequest.builder().messages(UserMessage.from("short question")).build();

        var sent = RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null)).doChat(request);

        assertThat(sent.aiMessage().text()).isEqualTo("fine");
        assertThat(reported).isEmpty();
        assertThat(states).isEmpty();
    }

    @Test void aRequestThatStillHasRoomWaitsOneBoundaryAndThenActs() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("late"));
        // Over soft (400) but far under hard, so one boundary may pass without spending work on it.
        var request = ChatRequest.builder().messages(UserMessage.from("u".repeat(1200))).build();
        var governor = governor(null, () -> true, new Settings(16_000, 8, 1, 2, 2));

        assertThat(governor.govern(request)).isSameAs(request);
        assertThat(reported).singleElement().extracting(Reduction::outcome).isEqualTo("DEFERRED");

        governor.govern(request);
        // Nothing in this request can be folded safely, and it still fits: the run continues unbothered.
        assertThat(reported).hasSize(2);
        assertThat(reported.get(1).outcome()).isEqualTo("NO_PROGRESS");
        assertThat(states).containsExactly("RUN_COMPACTING");
    }

    @Test void wholeToolRoundsAreFoldedAndTheNewestRoundStaysIntact() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("continued"));
        var request = ChatRequest.builder().messages(toolLoop()).build();
        var before = AgentRequestTokens.estimate(request, null);

        var sent = RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null, LOOP_BUDGET)).doChat(request);

        assertThat(sent.aiMessage().text()).isEqualTo("continued");
        var messages = captured(delegate).messages();
        assertThat(messages).hasSameSizeAs(request.messages());
        // Pairing survives: every call id still has its result, in the same order.
        assertThat(ToolRoundDigest.keepsProtocol(messages)).isTrue();
        assertThat(messages).filteredOn(ToolExecutionResultMessage.class::isInstance)
                .extracting(message -> ((ToolExecutionResultMessage) message).text())
                .satisfies(texts -> {
                    assertThat(texts.get(0)).startsWith(ToolRoundDigest.FENCE).doesNotContain("a".repeat(200));
                    assertThat(texts.get(1)).startsWith(ToolRoundDigest.FENCE);
                    assertThat(texts.get(2)).startsWith(ToolRoundDigest.FENCE);
                    // The round the model must reason about now is never summarized away.
                    assertThat(texts.getLast()).contains("d".repeat(200));
                });
        assertThat(messages).filteredOn(UserMessage.class::isInstance)
                .extracting(message -> ((UserMessage) message).singleText())
                .containsExactly("original goal", "latest correction");
        assertThat(reported).singleElement().satisfies(reduction -> {
            assertThat(reduction.outcome()).isEqualTo("REDUCED");
            assertThat(reduction.method()).isEqualTo("fold-tool-rounds");
            assertThat(reduction.foldedRounds()).isEqualTo(3);
            assertThat(reduction.afterTokens()).isLessThan(before);
        });
    }

    @Test void aFoldedWriteSaysItCompletedSoTheModelDoesNotPerformItTwice() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("done"));
        var writeCall = ToolExecutionRequest.builder().id("call-write").name("create_calendar_task")
                .arguments("{\"title\":\"周五面试\"}").build();
        var messages = new ArrayList<>(List.of(
                UserMessage.from("goal"),
                AiMessage.from(writeCall),
                ToolExecutionResultMessage.from("call-write", "create_calendar_task", "t".repeat(6000)),
                AiMessage.from(readCall("call-now", "list_calendar_tasks")),
                ToolExecutionResultMessage.from("call-now", "list_calendar_tasks", "u".repeat(600)),
                UserMessage.from("continue")));

        RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null, LOOP_BUDGET))
                .doChat(ChatRequest.builder().messages(messages).build());

        var folded = captured(delegate).messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(message -> (ToolExecutionResultMessage) message).toList().getFirst();
        assertThat(folded.text()).contains("create_calendar_task").contains("已完成").contains("call-write");
    }

    @Test void nothingLeftToFoldIsReportedInsteadOfSilentlySendingAnOversizedRequest() {
        var delegate = mock(ChatModel.class);
        var request = ChatRequest.builder().messages(UserMessage.from("one".repeat(4000))).build();

        assertThatThrownBy(() -> RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null)).doChat(request))
                .isInstanceOf(AgentContextLimitException.class)
                .hasMessageContaining("没有可以安全折叠的部分");
        verify(delegate, never()).doChat(any());
        assertThat(reported).singleElement().satisfies(reduction -> {
            assertThat(reduction.outcome()).isEqualTo("EXHAUSTED");
            assertThat(reduction.errorCode()).isEqualTo("CONTEXT_IRREDUCIBLE");
        });
    }

    @Test void anUnknownWindowReducesButNeverRefusesLocally() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("sent"));
        var budget = new AgentContextBudgetPolicy.Budget(null, 400, null, 100, 0);
        var governor = new RuntimeContextGovernor(budget, null, Settings.DEFAULTS, null, () -> true, observer);
        var messages = new ArrayList<>(List.of(
                UserMessage.from("goal"),
                AiMessage.from(readCall("call-1", "list_calendar_tasks")),
                ToolExecutionResultMessage.from("call-1", "list_calendar_tasks", "a".repeat(6000)),
                AiMessage.from(readCall("call-2", "list_memory_cards")),
                ToolExecutionResultMessage.from("call-2", "list_memory_cards", "b".repeat(6000)),
                UserMessage.from("and now?")));

        var response = governor.govern(ChatRequest.builder().messages(messages).build());

        assertThat(ToolRoundDigest.keepsProtocol(response.messages())).isTrue();
        assertThat(reported).singleElement().satisfies(reduction -> {
            assertThat(reduction.allowanceTokens()).isEqualTo(-1);
            assertThat(reduction.outcome()).isNotEqualTo("EXHAUSTED");
            assertThat(reduction.errorCode()).isNull();
        });
    }

    @Test void cancellationBeforeTheBoundarySendsNothing() {
        var delegate = mock(ChatModel.class);
        var governor = governor(null, () -> false, null);

        assertThatThrownBy(() -> governor.govern(ChatRequest.builder()
                .messages(UserMessage.from("q".repeat(400))).build()))
                .isInstanceOf(CancellationException.class);
        verify(delegate, never()).doChat(any());
    }

    @Test void aProviderLengthRefusalIsReducedAndRetriedExactlyOnce() {
        var delegate = mock(ChatModel.class);
        var first = ChatRequest.builder().messages(toolLoop()).build();
        when(delegate.doChat(any()))
                .thenThrow(new dev.langchain4j.exception.HttpException(
                        400, "{\"error\":{\"message\":\"This model's maximum context length is 4096 tokens\"}}"))
                .thenReturn(response("recovered"));
        var attempts = new AtomicInteger();
        RuntimeContextGovernor.Observer counting = new RuntimeContextGovernor.Observer() {
            @Override public void onState(String state) { attempts.incrementAndGet(); }

            @Override public void onReduction(Reduction reduction) { reported.add(reduction); }
        };
        var governor = new RuntimeContextGovernor(LOOP_BUDGET, null, Settings.DEFAULTS, null, () -> true, counting);

        var sent = RuntimeContextGovernor.chat(delegate, governor).doChat(first);

        assertThat(sent.aiMessage().text()).isEqualTo("recovered");
        verify(delegate, times(2)).doChat(any());
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test void aRateLimitFailureIsNotMistakenForALengthRefusal() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenThrow(new dev.langchain4j.exception.HttpException(429,
                "{\"error\":{\"message\":\"Too many requests\"}}"));

        assertThatThrownBy(() -> RuntimeContextGovernor.chat(
                delegate, governor(null, () -> true, null)).doChat(ChatRequest.builder()
                .messages(UserMessage.from("q".repeat(400))).build()))
                .isInstanceOf(dev.langchain4j.exception.HttpException.class);
        verify(delegate, times(1)).doChat(any());
    }

    @Test void theModelBackedStepOnlyRunsWhenTheDeterministicStepsFallShort() {
        var delegate = mock(ChatModel.class);
        when(delegate.doChat(any())).thenReturn(response("after summary"));
        var summarized = new AtomicInteger();
        RuntimeContextGovernor.RunTextSummarizer summarizer = turns -> {
            summarized.incrementAndGet();
            assertThat(turns).allMatch(text -> text.length() > 400);
            return "目标：完成投递清单；约束：只改周五的日程；已读取 3 份简历。";
        };
        // Old narration that no folding step can reach: no tool rounds, only earlier text turns.
        var messages = new ArrayList<ChatMessage>(List.of(
                UserMessage.from("goal that stays verbatim"),
                AiMessage.from("narration ".repeat(70)),
                AiMessage.from("more narration ".repeat(50)),
                AiMessage.from("even more narration ".repeat(40)),
                AiMessage.from("older step ".repeat(60)),
                AiMessage.from("still older step ".repeat(45)),
                UserMessage.from("latest correction")));

        var sent = RuntimeContextGovernor.chat(delegate, governor(summarizer, () -> true, null, LOOP_BUDGET))
                .doChat(ChatRequest.builder().messages(messages).build());

        assertThat(sent.aiMessage().text()).isEqualTo("after summary");
        var outgoing = captured(delegate).messages();
        assertThat(outgoing).filteredOn(UserMessage.class::isInstance)
                .extracting(message -> ((UserMessage) message).singleText())
                .contains("goal that stays verbatim", "latest correction");
        assertThat(outgoing).anyMatch(message -> String.valueOf(message).contains("run_text_digest")
                && String.valueOf(message).contains("只改周五的日程"));
        assertThat(summarized).hasValue(1);
        assertThat(reported).singleElement().satisfies(reduction ->
                assertThat(reduction.method()).isEqualTo("summarize-run-text"));
    }

    @Test void summaryCallsAreCappedForTheWholeRun() {
        var summarizer = mock(RuntimeContextGovernor.RunTextSummarizer.class);
        when(summarizer.summarize(any())).thenReturn("摘要");
        var oversized = ChatRequest.builder().messages(List.of(
                UserMessage.from("goal"), AiMessage.from("narration ".repeat(50)),
                AiMessage.from("more ".repeat(40)), AiMessage.from("even more ".repeat(50)),
                AiMessage.from("and more ".repeat(56)), AiMessage.from("older step ".repeat(45)),
                AiMessage.from("last narration ".repeat(40)), UserMessage.from("now?"))).build();
        // Measured, not assumed: over the soft threshold and without room for another round, yet still
        // sendable, so the cap alone decides when the run stops paying for summaries.
        long measured = AgentRequestTokens.estimate(oversized, null);
        var budget = new AgentContextBudgetPolicy.Budget(64000L, measured / 2, measured + 20, 100, 50);
        // No deferred boundary, so every press on the soft threshold reaches the ladder on its own.
        var governor = governor(summarizer, () -> true, new Settings(16_000, 8, 0, 2, 2), budget);

        governor.govern(oversized);
        governor.govern(oversized);
        governor.govern(oversized);

        // One summary per boundary, at most two per run: a run must not spend calls folding forever.
        verify(summarizer, times(2)).summarize(any());
    }

    @Test void foldingIsAbandonedWhenItWouldBreakTheToolPairing() {
        var delegate = mock(ChatModel.class);
        // A result whose call id is nowhere in the request: the pairing is already broken, so the
        // governor must not rewrite anything and pretend the request is safe.
        var messages = new ArrayList<ChatMessage>(List.of(
                UserMessage.from("goal"),
                ToolExecutionResultMessage.from("orphan", "list_calendar_tasks", "a".repeat(6000)),
                AiMessage.from(readCall("call-1", "list_memory_cards")),
                ToolExecutionResultMessage.from("call-1", "list_memory_cards", "b".repeat(6000)),
                UserMessage.from("next")));

        assertThatThrownBy(() -> RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null, LOOP_BUDGET))
                .doChat(ChatRequest.builder().messages(messages).build()))
                .isInstanceOf(AgentContextLimitException.class);
        verify(delegate, never()).doChat(any());
        assertThat(reported).singleElement().satisfies(reduction -> {
            assertThat(reduction.outcome()).isEqualTo("EXHAUSTED");
            assertThat(reduction.method()).isEmpty();
        });
    }

    @Test void systemPromptAndToolSchemasArePartOfWhatGetsMeasured() {
        var delegate = mock(ChatModel.class);
        var tool = dev.langchain4j.agent.tool.ToolSpecification.builder().name("list_calendar_tasks")
                .description("tool schema ".repeat(300)).build();
        var request = ChatRequest.builder()
                .toolSpecifications(List.of(tool))
                .messages(SystemMessage.from("system prompt ".repeat(300)), UserMessage.from("q".repeat(400)))
                .build();

        // Nothing in this request is foldable, and the refusal is what proves the schema and prompt counted.
        assertThatThrownBy(() -> RuntimeContextGovernor.chat(delegate, governor(null, () -> true, null, LOOP_BUDGET))
                .doChat(request)).isInstanceOf(AgentContextLimitException.class);
        assertThat(reported).singleElement().satisfies(reduction -> {
            assertThat(reduction.beforeTokens()).isGreaterThan(LOOP_BUDGET.hardInputTokens());
            assertThat(reduction.outcome()).isEqualTo("EXHAUSTED");
        });
        verify(delegate, never()).doChat(any());
    }

    private static List<ChatMessage> toolLoop() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("original goal"));
        String[] payloads = {"a".repeat(4000), "b".repeat(4000), "c".repeat(4000), "d".repeat(400)};
        for (int index = 0; index < payloads.length; index++) {
            String id = "call-" + index;
            messages.add(AiMessage.from(readCall(id, "list_calendar_tasks")));
            messages.add(ToolExecutionResultMessage.from(id, "list_calendar_tasks", payloads[index]));
        }
        messages.add(UserMessage.from("latest correction"));
        return messages;
    }

    private static ToolExecutionRequest readCall(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static ChatRequest captured(ChatModel delegate) {
        var captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(delegate).doChat(captor.capture());
        return captor.getValue();
    }
}

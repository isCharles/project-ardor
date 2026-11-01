package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.execution.service.LangChainModelFactory;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

class ContextSummarizerTests {
    private final ChatModel model = mock(ChatModel.class);
    private final LangChainModelFactory factory = mock(LangChainModelFactory.class);
    private final UUID userId = UUID.randomUUID();

    ContextSummarizerTests() {
        when(factory.createForSummarization(any(), any(LlmConfigService.LlmRuntimeConfig.class),
                any(ReasoningDepth.class), anyList())).thenReturn(model);
    }

    @Test void coverageStopsWhereTheSummarizingCallRunsOutOfRoom() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("summary"));

        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(14, 3_000), (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.coveredIds()).hasSize(4);
        assertThat(outcome.attempts()).isEqualTo(1);
    }

    @Test void oneOversizedBlockIsStillFedInWholeRatherThanSummarizingNothing() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("summary"));
        var chosen = new AtomicReference<List<ContextSummarizer.Block>>();

        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(6, 13_000), (existing, picked) -> {
                    chosen.set(picked);
                    return "prompt";
                }, null, List.of(), () -> true);

        assertThat(outcome.coveredIds()).containsExactly("0");
        assertThat(chosen.get()).singleElement().extracting(ContextSummarizer.Block::text)
                .isEqualTo("0".repeat(13_000));
    }

    @Test void aSummaryCutOffByTheOutputCeilingIsNotTrusted() {
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("half a summary")).finishReason(FinishReason.LENGTH).build());

        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(6, 100), (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.errorCode()).isEqualTo("COMPACTION_OUTPUT_TRUNCATED");
        assertThat(outcome.coveredIds()).isEmpty();
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test void anOverlongSummaryIsRejectedInsteadOfBeingTruncatedIntoThePrompt() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("x".repeat(500)));

        var outcome = summarizer(12_000, 100).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(6, 100), (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.errorCode()).isEqualTo("COMPACTION_SUMMARY_TOO_LONG");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test void anUnreachableModelIsReportedWithoutInventingAnEmptySummary() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("connection reset"));

        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(3, 100), (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.errorCode()).isEqualTo("COMPACTION_MODEL_UNAVAILABLE");
        assertThat(outcome.summary()).isNull();
    }

    @Test void aPromptThatCannotFitTheWindowSaysSoRatherThanLookingLikeAnOutage() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new com.projectardor.assistant.execution.service
                .AgentContextLimitException("CONTEXT_LIMIT", "too big"));

        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(3, 100), (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.errorCode()).isEqualTo("COMPACTION_INPUT_TOO_LARGE");
    }

    @Test void aCancelledRunKeepsItsLateSummaryOutOfThePrompt() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("too late"));
        var firstCheck = new AtomicBoolean(true);

        assertThatThrownBy(() -> summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(3, 100), (existing, chosen) -> "prompt", null, List.of(),
                () -> !firstCheck.compareAndSet(true, false)))
                .isInstanceOf(CancellationException.class);
    }

    @Test void theNormalizeStepDecidesWhatLengthIsMeasured() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("{\"verbose\":\"" + "y".repeat(400) + "\"}"));

        var outcome = summarizer(12_000, 50).summarize(userId, config(), ReasoningDepth.AUTO, "",
                blocks(3, 100), (existing, chosen) -> "prompt", raw -> "short", List.of(), () -> true);

        // The raw JSON is far over the ceiling; the parsed form is what gets kept and measured.
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.summary()).isEqualTo("short");
    }

    @Test void nothingToSummarizeIsAnEmptyRequestRatherThanAFailedAttempt() {
        var outcome = summarizer(12_000, 4_000).summarize(userId, config(), ReasoningDepth.AUTO, "", List.of(),
                (existing, chosen) -> "prompt", null, List.of(), () -> true);

        assertThat(outcome.errorCode()).isEqualTo("COMPACTION_NOTHING_TO_SUMMARIZE");
        verify(model, never()).chat(any(ChatRequest.class));
    }

    private ContextSummarizer summarizer(int inputChars, int outputChars) {
        return new ContextSummarizer(factory, inputChars, outputChars);
    }

    private static List<ContextSummarizer.Block> blocks(int count, int size) {
        List<ContextSummarizer.Block> blocks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            blocks.add(new ContextSummarizer.Block(String.valueOf(index), String.valueOf(index % 10)
                    .repeat(1) + "0".repeat(size - 1)));
        }
        return blocks;
    }

    private static ChatResponse response(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).build();
    }

    private static LlmConfigService.LlmRuntimeConfig config() {
        return new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid",
                "fixture-model", "redacted");
    }
}

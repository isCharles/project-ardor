package com.projectardor.assistant.memory.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.execution.service.LangChainModelFactory;
import com.projectardor.platform.model.llm.service.LlmConfigService;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * The one place that asks a model to shorten text, for history at chat start and for a run's own
 * execution trace in the middle of that run.
 *
 * <p>Both callers face the same three problems, so they share the handling: the blocks that may be
 * summarized are limited by how much text the summarizing call itself can carry, a summary that comes
 * back cut off or oversized is not trusted, and a cancellation must not leave a late summary behind.
 * Coverage is reported as the exact blocks that were fed in, so a caller never marks text as
 * represented that the model never saw.</p>
 *
 * <p>Constructed by its callers rather than registered as a bean: the model it needs comes from
 * {@link LangChainModelFactory}, and the factory asks for summaries back.</p>
 *
 * <p>This is a best-effort reduction: whether the replacement is short enough is the caller's
 * measurement, not a promise here. Nothing is persisted and no permission changes — the summary is
 * untrusted generated text that the caller must fence when it re-enters a prompt.</p>
 */
public class ContextSummarizer {
    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);
    private static final int MAX_ATTEMPTS = 2;

    /** One unit to summarize; {@code id} lets the caller map coverage back to its own records. */
    public record Block(String id, String text) {}

    /**
     * @param coveredIds the blocks actually handed to the model, in order; empty when nothing was tried
     * @param errorCode  null only when a summary was produced
     */
    public record Outcome(String summary, List<String> coveredIds, String errorCode, int attempts) {
        public boolean succeeded() {
            return summary != null && !summary.isBlank() && errorCode == null;
        }

        static Outcome failed(String code, int attempts) {
            return new Outcome(null, List.of(), code, attempts);
        }
    }

    private final LangChainModelFactory modelFactory;
    private final int inputCharBudget;
    private final int outputCharLimit;

    public ContextSummarizer(LangChainModelFactory modelFactory, int inputCharBudget, int outputCharLimit) {
        this.modelFactory = modelFactory;
        this.inputCharBudget = Math.max(500, inputCharBudget);
        this.outputCharLimit = Math.max(200, outputCharLimit);
    }

    /**
     * @param prompt  builds the instruction for the chosen blocks, carrying any earlier summary forward
     * @param normalize turns the model's raw reply into the text that will be measured and kept, such as
     *                  parsing a structured summary; identity when the raw text is the summary
     */
    public Outcome summarize(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            String existingSummary, List<Block> blocks, BiFunction<String, List<Block>, String> prompt,
            UnaryOperator<String> normalize, List<ChatModelListener> listeners, BooleanSupplier active) {
        List<Block> eligible = withinInputBudget(blocks);
        if (eligible.isEmpty()) return Outcome.failed("COMPACTION_NOTHING_TO_SUMMARIZE", 0);
        UnaryOperator<String> keep = normalize == null ? text -> text : normalize;
        String carried = existingSummary == null ? "" : existingSummary;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            requireActive(active);
            ChatResponse response;
            try {
                ChatModel model = modelFactory.createForSummarization(userId, config, depth, listeners);
                response = model.chat(ChatRequest.builder()
                        .messages(UserMessage.from(prompt.apply(carried, eligible))).build());
            } catch (java.util.concurrent.CancellationException stopped) {
                throw stopped;
            } catch (com.projectardor.assistant.execution.service.AgentContextLimitException tooLarge) {
                // The fence stopped a request that was provably over the window: the chunking failed, not
                // the provider. Saying "model unavailable" here would send an operator to the wrong door.
                log.warn("Context summarization input exceeded the model window: blocks={}", eligible.size());
                return Outcome.failed("COMPACTION_INPUT_TOO_LARGE", attempt);
            } catch (RuntimeException unavailable) {
                requireActive(active);
                log.warn("Context summarization unavailable: type={}", unavailable.getClass().getSimpleName());
                return Outcome.failed("COMPACTION_MODEL_UNAVAILABLE", attempt);
            }
            String raw = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
            if (raw == null || raw.isBlank()) return Outcome.failed("COMPACTION_EMPTY_SUMMARY", attempt);
            requireActive(active);
            String text = keep.apply(raw);
            if (text == null || text.isBlank()) return Outcome.failed("COMPACTION_EMPTY_SUMMARY", attempt);
            boolean truncated = isOutputTruncated(response);
            if (text.length() > outputCharLimit || truncated) {
                String code = truncated ? "COMPACTION_OUTPUT_TRUNCATED" : "COMPACTION_SUMMARY_TOO_LONG";
                log.info("Context summary rejected: code={}, length={}, attempt={}", code, text.length(), attempt);
                if (attempt == MAX_ATTEMPTS || eligible.size() == 1) return Outcome.failed(code, attempt);
                eligible = eligible.subList(0, Math.max(1, eligible.size() / 2));
                continue;
            }
            return new Outcome(text, eligible.stream().map(Block::id).toList(), null, attempt);
        }
        return Outcome.failed("COMPACTION_RETRY_EXHAUSTED", MAX_ATTEMPTS);
    }

    /** The largest prefix of blocks the summarizing call can carry; a single block is never split here. */
    private List<Block> withinInputBudget(List<Block> blocks) {
        List<Block> selected = new ArrayList<>(blocks.size());
        int used = 0;
        for (Block block : blocks) {
            int length = block.text() == null ? 0 : block.text().length();
            if (!selected.isEmpty() && used + length > inputCharBudget) break;
            selected.add(block);
            used += length;
        }
        return selected;
    }

    private static void requireActive(BooleanSupplier active) {
        if (active != null && (!active.getAsBoolean() || Thread.currentThread().isInterrupted())) {
            throw new java.util.concurrent.CancellationException("本轮已停止，未保留迟到的摘要");
        }
    }

    /** A summary cut off by the output ceiling is missing its tail, so it cannot be trusted as complete. */
    public static boolean isOutputTruncated(ChatResponse response) {
        if (response == null || response.metadata() == null || response.metadata().finishReason() == null) return false;
        String reason = response.metadata().finishReason().name();
        return "LENGTH".equals(reason) || "MAX_TOKENS".equals(reason);
    }
}

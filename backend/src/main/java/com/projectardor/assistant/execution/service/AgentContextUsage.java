package com.projectardor.assistant.execution.service;

/**
 * Latest request input usage (estimated until provider usage arrives), never cumulative billing.
 * {@code authoritative} marks provider-reported input; {@code conversationAnchor} marks the first
 * provider response of a run, whose input excludes transient tool results. The context ring reads
 * only the anchor so tool rounds cannot make the displayed conversation size rise and fall.
 * {@code reduction} describes what the run just did to stay inside the window, if anything.
 */
public record AgentContextUsage(String state, String model, Long estimatedTokens, int compressionBudgetTokens,
        Long modelWindowTokens, int summaryVersion, int compactedMessages, Compaction compaction,
        boolean authoritative, boolean conversationAnchor, RuntimeReduction reduction) {
    public AgentContextUsage(String state, String model, Long estimatedTokens, int compressionBudgetTokens,
            Long modelWindowTokens, int summaryVersion, int compactedMessages, Compaction compaction) {
        this(state, model, estimatedTokens, compressionBudgetTokens, modelWindowTokens, summaryVersion,
                compactedMessages, compaction, false, false, null);
    }

    public AgentContextUsage(String state, String model, Long estimatedTokens, int compressionBudgetTokens,
            Long modelWindowTokens, int summaryVersion, int compactedMessages, Compaction compaction,
            boolean authoritative, boolean conversationAnchor) {
        this(state, model, estimatedTokens, compressionBudgetTokens, modelWindowTokens, summaryVersion,
                compactedMessages, compaction, authoritative, conversationAnchor, null);
    }

    public static AgentContextUsage preparing(String model) {
        return new AgentContextUsage("PREPARING", model, null, 0, null, 0, 0, null);
    }

    /** One compaction of stored history, with the figures a reader can recheck against the trace span. */
    public record Compaction(String outcome, String spanId, Long windowTokens, long softThresholdTokens,
            long projectedInputTokens, long estimatedInputTokensAfter, int summarizedMessages,
            int firstMessageOrdinal, int summaryVersionBefore, int summaryVersionAfter, String errorCode) {
        public boolean succeeded() { return "COMPACTED".equals(outcome); }
        public int lastMessageOrdinal() { return firstMessageOrdinal + Math.max(0, summarizedMessages) - 1; }
    }

    /**
     * One context decision taken in the middle of a run, at a model-call boundary.
     *
     * @param method the steps that actually shrank the request, joined; empty when nothing helped
     * @param allowanceTokens -1 when the model window is unknown, so nothing could be proven to overflow
     * @param estimated whether the figures are Ardor's own token estimate rather than provider counts
     */
    public record RuntimeReduction(String outcome, String method, Long beforeTokens, long afterTokens,
            long allowanceTokens, long softThresholdTokens, int foldedRounds, int summarizedTurns, int attempts,
            boolean estimated, String errorCode, String spanId) {
        public boolean reduced() {
            return beforeTokens != null && afterTokens < beforeTokens;
        }
    }
}

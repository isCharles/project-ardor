package com.projectardor.assistant.execution.service;

/** Latest request input usage (estimated until provider usage arrives), never cumulative billing. */
public record AgentContextUsage(String state, String model, Long estimatedTokens, int compressionBudgetTokens,
        Long modelWindowTokens, int summaryVersion, int compactedMessages, Compaction compaction) {
    public static AgentContextUsage preparing(String model) {
        return new AgentContextUsage("PREPARING", model, null, 0, null, 0, 0, null);
    }

    /**
     * One context compaction with the figures a reader can recheck: the projected input that crossed the
     * threshold, what the prompt costs afterwards, which history messages the summary now stands for and
     * the trace span holding the same numbers. {@code null} errorCode means the summary was written.
     */
    public record Compaction(String outcome, String spanId, Long windowTokens, long softThresholdTokens,
            long projectedInputTokens, long estimatedInputTokensAfter, int summarizedMessages,
            int firstMessageOrdinal, int summaryVersionBefore, int summaryVersionAfter, String errorCode) {
        public boolean succeeded() { return "COMPACTED".equals(outcome); }
        public int lastMessageOrdinal() { return firstMessageOrdinal + Math.max(0, summarizedMessages) - 1; }
    }
}

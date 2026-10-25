package com.projectardor.assistant.execution.service;

/** Prompt assembly estimate, not provider billing or a claimed model capacity. */
public record AgentContextUsage(String state, String model, Long estimatedTokens, int compressionBudgetTokens,
        Long modelWindowTokens, int summaryVersion, int compactedMessages) {
    public static AgentContextUsage preparing(String model) {
        return new AgentContextUsage("PREPARING", model, null, 0, null, 0, 0);
    }
}

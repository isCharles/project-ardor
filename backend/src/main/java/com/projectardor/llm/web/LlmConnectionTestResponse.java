package com.projectardor.llm.web;

import com.projectardor.llm.domain.LlmProvider;

public record LlmConnectionTestResponse(
        boolean success,
        LlmProvider provider,
        String model,
        long latencyMs,
        String message) {
}

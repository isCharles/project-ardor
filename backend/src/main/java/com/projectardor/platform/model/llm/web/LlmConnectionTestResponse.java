package com.projectardor.platform.model.llm.web;

import com.projectardor.platform.model.llm.domain.LlmProvider;

public record LlmConnectionTestResponse(
        boolean success,
        LlmProvider provider,
        String model,
        long latencyMs,
        String message) {
}

package com.projectardor.assistant.execution.service;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/**
 * One model call's usage. Anthropic reports cached tokens separately from {@code input_tokens};
 * a null cache field means the provider did not report it, never that it was zero.
 */
public record LlmUsage(Integer inputTokens, Integer outputTokens,
        Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {

    public static LlmUsage of(TokenUsage usage) {
        if (usage == null) return new LlmUsage(null, null, null, null);
        if (usage instanceof AnthropicTokenUsage anthropic) {
            return new LlmUsage(usage.inputTokenCount(), usage.outputTokenCount(),
                    anthropic.cacheReadInputTokens(), anthropic.cacheCreationInputTokens());
        }
        return new LlmUsage(usage.inputTokenCount(), usage.outputTokenCount(), null, null);
    }

    public boolean hasCacheFields() {
        return cacheReadInputTokens != null || cacheCreationInputTokens != null;
    }
}

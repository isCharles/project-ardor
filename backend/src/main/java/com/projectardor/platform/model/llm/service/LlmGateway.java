package com.projectardor.platform.model.llm.service;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface LlmGateway {

    LlmResult completeJson(UUID userId, String systemPrompt, String userPrompt);

    /**
     * Reports model text while the model is still writing it, so a caller can start acting on the
     * first sentence instead of waiting for the document. A gateway whose transport cannot stream
     * returns the whole document in one callback, which keeps a single contract for callers.
     */
    default LlmResult completeJsonStreaming(
            UUID userId,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) {
        LlmResult result = completeJson(userId, systemPrompt, userPrompt);
        if (!stopRequested.getAsBoolean()) onDelta.accept(result.content());
        return result;
    }

    record LlmResult(String content, String model) {
    }
}

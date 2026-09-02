package com.projectardor.llm.service;

import java.util.UUID;

public interface LlmGateway {

    LlmResult completeJson(UUID userId, String systemPrompt, String userPrompt);

    record LlmResult(String content, String model) {
    }
}

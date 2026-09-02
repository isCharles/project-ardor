package com.projectardor.llm.web;

import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.domain.LlmProviderConfig;

public record LlmConfigResponse(
        boolean configured,
        LlmProvider provider,
        String baseUrl,
        String model,
        String keyHint) {

    public static LlmConfigResponse configured(LlmProviderConfig config) {
        return new LlmConfigResponse(
                true,
                config.getProvider(),
                config.getBaseUrl(),
                config.getModel(),
                config.getKeyHint());
    }

    public static LlmConfigResponse unconfigured() {
        return new LlmConfigResponse(
                false,
                LlmProvider.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                null);
    }
}

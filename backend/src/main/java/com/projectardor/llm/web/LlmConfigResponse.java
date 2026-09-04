package com.projectardor.llm.web;

import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.domain.LlmProviderConfig;

public record LlmConfigResponse(
        boolean configured,
        LlmProvider provider,
        String baseUrl,
        String model,
        String keyHint,
        String configurationSource,
        boolean personalOverride) {

    public static LlmConfigResponse configured(LlmProviderConfig config) {
        return new LlmConfigResponse(
                true,
                config.getProvider(),
                config.getBaseUrl(),
                config.getModel(),
                config.getKeyHint(), "PERSONAL", true);
    }

    public static LlmConfigResponse inherited(LlmProvider provider, String baseUrl, String model, String keyHint) {
        return new LlmConfigResponse(true, provider, baseUrl, model, keyHint, "ADMIN", false);
    }

    public static LlmConfigResponse unconfigured() {
        return new LlmConfigResponse(
                false,
                LlmProvider.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                null, "NONE", false);
    }
}

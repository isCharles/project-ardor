package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

class ModelConfigFingerprintTests {

    @Test
    void identifiesProviderEndpointAndModelButNotTheApiKey() {
        var config = config(LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com/", "deepseek-v4-flash", "first");
        var rotatedKey = config(LlmProvider.OPENAI_COMPATIBLE, "https://API.deepseek.com", "deepseek-v4-flash", "second");
        var otherModel = config(LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-pro", "first");

        assertThat(ModelConfigFingerprint.of(config)).matches("[0-9a-f]{64}");
        assertThat(ModelConfigFingerprint.of(config)).isEqualTo(ModelConfigFingerprint.of(rotatedKey));
        assertThat(ModelConfigFingerprint.of(config)).isNotEqualTo(ModelConfigFingerprint.of(otherModel));
        assertThat(ModelConfigFingerprint.of(config)).doesNotContain("first");
    }

    @Test
    void toleratesMissingOptionalFields() {
        var config = config(LlmProvider.ANTHROPIC_COMPATIBLE, null, null, "synthetic");

        assertThat(ModelConfigFingerprint.of(config)).matches("[0-9a-f]{64}");
    }

    private static LlmConfigService.LlmRuntimeConfig config(LlmProvider provider, String baseUrl, String model,
            String apiKey) {
        return new LlmConfigService.LlmRuntimeConfig(provider, baseUrl, model, apiKey);
    }
}

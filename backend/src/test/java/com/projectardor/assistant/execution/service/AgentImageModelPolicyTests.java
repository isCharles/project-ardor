package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

class AgentImageModelPolicyTests {
    @ParameterizedTest
    @ValueSource(strings = { "gpt-4o", "openai/gpt-5.1", "o3-mini", "claude-sonnet-4-5",
            "gemini-2.5-flash", "qwen2.5-vl-72b-instruct", "deepseek-flash", "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp", "deepseek-v4.1-flash", "deepseek/deepseek-v4.1-flash" })
    void acceptsKnownImageModelFamilies(String model) {
        assertThat(AgentImageModelPolicy.supports(config(model))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "gpt-3.5-turbo", "deepseek-chat", "deepseek-v4-pro", "qwen2.5-72b-instruct", "unknown-model" })
    void rejectsModelsWithoutDeclaredImageSupport(String model) {
        assertThat(AgentImageModelPolicy.supports(config(model))).isFalse();
        assertThatThrownBy(() -> AgentImageModelPolicy.requireSupport(config(model)))
                .hasMessage("当前模型未声明支持图片，请切换支持图片的模型");
    }

    private static LlmConfigService.LlmRuntimeConfig config(String model) {
        return new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", model, "synthetic-only");
    }
}

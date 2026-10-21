package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;

class ModelReasoningPolicyTests {
    private LlmRuntimeConfig config(LlmProvider provider, String model) {
        return new LlmRuntimeConfig(provider, "https://example.com", model, "synthetic-key");
    }

    @Test void openAiUsesNativeEffortWithoutSendingUnknownFields() {
        var result = ModelReasoningPolicy.resolve(config(LlmProvider.OPENAI_COMPATIBLE, "gpt-5.1"), ReasoningDepth.DEEP);
        assertThat(result.effort()).isEqualTo("high");
        assertThat(result.extra()).isEmpty();
        assertThat(result.nativeControl()).isTrue();
    }

    @Test void deepSeekUsesItsOwnHighAndMaxMappingAndPreservesToolThinking() {
        var config = config(LlmProvider.OPENAI_COMPATIBLE, "deepseek-v4-flash");
        assertThat(ModelReasoningPolicy.resolve(config, ReasoningDepth.STANDARD).effort()).isEqualTo("high");
        var result = ModelReasoningPolicy.resolve(config, ReasoningDepth.DEEP);
        assertThat(result.effort()).isEqualTo("max");
        assertThat(result.extra()).containsKey("thinking");
        assertThat(result.sendThinking()).isTrue();
    }

    @Test void claudeAdaptsToNewAndOlderThinkingProtocols() {
        var adaptive = ModelReasoningPolicy.resolve(config(LlmProvider.ANTHROPIC_COMPATIBLE, "claude-opus-4-6"), ReasoningDepth.STANDARD);
        assertThat(adaptive.thinkingType()).isEqualTo("adaptive");
        assertThat(adaptive.extra()).containsKey("output_config");
        assertThat(adaptive.budget()).isNull();
        var older = ModelReasoningPolicy.resolve(config(LlmProvider.ANTHROPIC_COMPATIBLE, "claude-sonnet-4-5"), ReasoningDepth.DEEP);
        assertThat(older.budget()).isEqualTo(8192);
        assertThat(older.maxTokens()).isGreaterThan(older.budget());
    }

    @Test void unknownModelsUseGuidanceAndAutoKeepsProviderDefaults() {
        var unknown = ModelReasoningPolicy.resolve(config(LlmProvider.OPENAI_COMPATIBLE, "custom-model"), ReasoningDepth.DEEP);
        assertThat(unknown.nativeControl()).isFalse();
        assertThat(unknown.effort()).isNull();
        assertThat(unknown.extra()).isEmpty();
        var auto = ModelReasoningPolicy.resolve(config(LlmProvider.OPENAI_COMPATIBLE, "gpt-5"), null);
        assertThat(auto.effort()).isNull();
    }

    @Test void laterClaudeFourVersionsKeepAdaptiveThinking() {
        for (String model : new String[] {"claude-opus-4-7", "claude-sonnet-4-8", "claude-opus-4.7", "claude-opus-4-10-20261012"}) {
            var result = ModelReasoningPolicy.resolve(config(LlmProvider.ANTHROPIC_COMPATIBLE, model), ReasoningDepth.DEEP);
            assertThat(result.thinkingType()).as(model).isEqualTo("adaptive");
            assertThat(result.budget()).isNull();
        }
    }
}

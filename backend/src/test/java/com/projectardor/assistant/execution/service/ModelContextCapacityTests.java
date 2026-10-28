package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;
import com.projectardor.platform.model.llm.domain.LlmProvider;

class ModelContextCapacityTests {
    @Test void requiresExactOfficialIdentityOrExplicitConfiguration() {
        var env = new MockEnvironment();
        var policy = new ModelContextCapacity(env);
        assertThat(policy.resolve(config("https://api.deepseek.com", "deepseek-v4-flash"))).isEqualTo(1048576L);
        assertThat(policy.resolve(config("https://relay.example", "deepseek-v4-flash"))).isNull();
        assertThat(policy.resolve(config("https://api.deepseek.com", "deepseek-v4-flash-custom"))).isNull();
        env.setProperty("app.agent.context.windows.deepseek-v4-flash", "64000");
        assertThat(policy.resolve(config("https://relay.example", "deepseek-v4-flash"))).isEqualTo(64000L);
        env.setProperty("app.agent.context.windows.deepseek-v4-flash", "-1");
        assertThat(policy.resolve(config("https://api.deepseek.com", "deepseek-v4-flash"))).isNull();
    }

    @Test void aUserWindowOverridesBuiltInAndEnvironmentLimitsOnlyForThatOwner() {
        var env = new MockEnvironment();
        var settings = mock(UserModelSettingsStore.class);
        UUID user = UUID.randomUUID();
        var config = config("https://api.deepseek.com", "deepseek-v4-flash");
        when(settings.findContextWindow(any(), any())).thenReturn(null);
        when(settings.findContextWindow(user, ModelConfigFingerprint.of(config))).thenReturn(200_000L);
        var policy = new ModelContextCapacity(env, settings);

        assertThat(policy.resolve(user, config)).isEqualTo(200_000L);
        assertThat(policy.resolve(UUID.randomUUID(), config)).isEqualTo(1_048_576L);
        assertThat(policy.resolve((UUID) null, config)).isEqualTo(1_048_576L);
        assertThat(policy.resolve(user, config("https://relay.example", "deepseek-v4-flash"))).isNull();
    }

    @Test void aFailingSettingsLookupNeverBlocksTheBuiltInLimit() {
        var env = new MockEnvironment();
        var settings = mock(UserModelSettingsStore.class);
        when(settings.findContextWindow(any(), any())).thenThrow(new IllegalStateException("synthetic outage"));
        var policy = new ModelContextCapacity(env, settings);

        assertThat(policy.resolve(UUID.randomUUID(), config("https://api.deepseek.com", "deepseek-v4-flash")))
                .isEqualTo(1_048_576L);
    }

    private static LlmRuntimeConfig config(String base, String model) {
        return new LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE, base, model, "synthetic");
    }
}

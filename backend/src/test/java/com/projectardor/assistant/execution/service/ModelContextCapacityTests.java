package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static LlmRuntimeConfig config(String base, String model) {
        return new LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE, base, model, "synthetic");
    }
}

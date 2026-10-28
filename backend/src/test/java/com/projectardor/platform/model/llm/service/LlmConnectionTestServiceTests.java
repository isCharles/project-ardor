package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.domain.LlmProvider;

class LlmConnectionTestServiceTests {

    @Test
    void connectionTestChargesTheAuthenticatedUser() {
        var configService = mock(LlmConfigService.class);
        var gateway = mock(CompatibleLlmGateway.class);
        var probe = mock(ToolCallingProbe.class);
        UUID user = UUID.randomUUID();
        var config = new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://llm.example", "model-a", "test-key");
        when(gateway.completeJson(eq(user), eq(config), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("ok", "model-a"));

        var response = new LlmConnectionTestService(configService, gateway, probe).test(user, config);

        verify(gateway).completeJson(eq(user), eq(config), anyString(), anyString());
        verify(probe).verify(config, user);
        assertThat(response.success()).isTrue();
        assertThat(response.model()).isEqualTo("model-a");
    }
}

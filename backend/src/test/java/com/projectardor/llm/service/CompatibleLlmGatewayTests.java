package com.projectardor.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.projectardor.llm.domain.LlmProvider;

import tools.jackson.databind.ObjectMapper;

class CompatibleLlmGatewayTests {

    @Test
    void callsOpenAiCompatibleEndpoint() {
        UUID userId = UUID.randomUUID();
        TestGateway testGateway = gateway(userId, LlmProvider.OPENAI_COMPATIBLE, "deepseek-chat");
        testGateway.server.expect(once(), requestTo("https://llm.example/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess("""
                        {"model":"deepseek-chat","choices":[{"message":{"content":"{\\"ok\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        LlmGateway.LlmResult result = testGateway.gateway.completeJson(userId, "system", "user");

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        assertThat(result.model()).isEqualTo("deepseek-chat");
        testGateway.server.verify();
    }

    @Test
    void callsAnthropicCompatibleEndpoint() {
        UUID userId = UUID.randomUUID();
        TestGateway testGateway = gateway(userId, LlmProvider.ANTHROPIC_COMPATIBLE, "deepseek-chat");
        testGateway.server.expect(once(), requestTo("https://llm.example/v1/messages"))
                .andExpect(method(POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.max_tokens").value(4096))
                .andExpect(jsonPath("$.system").value("system"))
                .andRespond(withSuccess("""
                        {"model":"deepseek-chat","content":[{"type":"text","text":"{\\"ok\\":true}"}]}
                        """, MediaType.APPLICATION_JSON));

        LlmGateway.LlmResult result = testGateway.gateway.completeJson(userId, "system", "user");

        assertThat(result.content()).isEqualTo("{\"ok\":true}");
        testGateway.server.verify();
    }

    private TestGateway gateway(UUID userId, LlmProvider provider, String model) {
        LlmConfigService configService = mock(LlmConfigService.class);
        when(configService.getRuntimeConfig(userId)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                provider, "https://llm.example", model, "test-key"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CompatibleLlmGateway gateway = new CompatibleLlmGateway(configService, new ObjectMapper(), builder);
        return new TestGateway(gateway, server);
    }

    private record TestGateway(
            CompatibleLlmGateway gateway,
            MockRestServiceServer server) {
    }
}

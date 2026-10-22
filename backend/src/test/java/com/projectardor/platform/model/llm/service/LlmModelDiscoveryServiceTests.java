package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.projectardor.common.security.ExternalBaseUrlPolicy;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.web.LlmModelListRequest;

import tools.jackson.databind.ObjectMapper;

class LlmModelDiscoveryServiceTests {

    @Test
    void listsModelsFromOpenAiCompatibleEndpointWithVersionedBaseUrl() {
        var config = mock(LlmConfigService.class);
        var policy = mock(ExternalBaseUrlPolicy.class);
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var service = new LlmModelDiscoveryService(config, policy, builder, new ObjectMapper());
        var user = java.util.UUID.randomUUID();
        when(config.apiKeyForTest(user, "test-key")).thenReturn("test-key");
        when(policy.normalizeAndValidate("https://cf.api.fan/v1")).thenReturn("https://cf.api.fan/v1");
        server.expect(requestTo("https://cf.api.fan/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"claude-opus-5.5"},{"id":"deepseek-v4-flash"},{"id":"claude-opus-5.5"}]}
                        """, MediaType.APPLICATION_JSON));

        var models = service.list(user, new LlmModelListRequest(
                LlmProvider.OPENAI_COMPATIBLE, "https://cf.api.fan/v1", "test-key"));

        assertThat(models).containsExactly("claude-opus-5.5", "deepseek-v4-flash");
        server.verify();
    }

    @Test
    void reportsUnsupportedModelsEndpointWithoutLeakingTheKey() {
        var config = mock(LlmConfigService.class);
        var policy = mock(ExternalBaseUrlPolicy.class);
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var service = new LlmModelDiscoveryService(config, policy, builder, new ObjectMapper());
        var user = java.util.UUID.randomUUID();
        when(config.apiKeyForTest(user, "secret-key")).thenReturn("secret-key");
        when(policy.normalizeAndValidate("https://relay.example/v1")).thenReturn("https://relay.example/v1");
        server.expect(requestTo("https://relay.example/v1/models"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.list(user, new LlmModelListRequest(
                LlmProvider.OPENAI_COMPATIBLE, "https://relay.example/v1", "secret-key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/models")
                .hasMessageNotContaining("secret-key");
        server.verify();
    }

    @Test
    void addsAnthropicVersionPathWhenTheConfiguredBaseUrlHasNoVersion() {
        var config = mock(LlmConfigService.class);
        var policy = mock(ExternalBaseUrlPolicy.class);
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var service = new LlmModelDiscoveryService(config, policy, builder, new ObjectMapper());
        var user = java.util.UUID.randomUUID();
        when(config.apiKeyForTest(user, "test-key")).thenReturn("test-key");
        when(policy.normalizeAndValidate("https://cf.api.fan")).thenReturn("https://cf.api.fan");
        server.expect(requestTo("https://cf.api.fan/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"claude-opus-5-5\"}]}", MediaType.APPLICATION_JSON));

        assertThat(service.list(user, new LlmModelListRequest(
                LlmProvider.ANTHROPIC_COMPATIBLE, "https://cf.api.fan", "test-key")))
                .containsExactly("claude-opus-5-5");
        server.verify();
    }
}

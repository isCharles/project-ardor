package com.projectardor.assistant.execution.service;

import com.projectardor.platform.model.llm.service.LlmCallException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamFailureTests {
    @Test void wrappedTransportTimeoutMustNeverTriggerAutomaticReplay() {
        Throwable failure = CareerAgentService.classifyStreamFailure(
                new RuntimeException(new java.net.http.HttpTimeoutException("fixture")));
        assertThat(failure).isInstanceOf(LlmCallException.class);
        LlmCallException classified = (LlmCallException) failure;
        assertThat(classified.getCode()).isEqualTo("AGENT_UPSTREAM_TIMEOUT");
        assertThat(classified.isRetryable()).isFalse();
    }

    @Test void unrelatedFailuresRetainTheirOriginalClassification() {
        Throwable failure = new IllegalArgumentException("fixture");
        assertThat(CareerAgentService.classifyStreamFailure(failure)).isSameAs(failure);
    }

    @Test void typedSdkFailuresUseSafeSharedCodesWithoutProviderBodies() {
        java.util.Map<Integer, String> codes = java.util.Map.of(400, "LLM_BAD_REQUEST", 401, "LLM_AUTH_FAILED",
                403, "LLM_AUTH_FAILED", 404, "LLM_NOT_FOUND", 429, "LLM_BUSY", 503, "LLM_HTTP_ERROR");
        codes.forEach((status, code) -> {
            var original = new dev.langchain4j.exception.HttpException(status, "secret-provider-body");
            var classified = (LlmCallException) CareerAgentService.classifyStreamFailure(new RuntimeException(original));
            assertThat(classified.getCode()).isEqualTo(code);
            assertThat(classified.getMessage()).doesNotContain("secret-provider-body");
            assertThat(classified.isRetryable()).isEqualTo(status == 429 || status >= 500);
        });
    }

    @Test void sdkExceptionsWithoutStatusStillHaveExplicitClassification() {
        assertCode(new dev.langchain4j.exception.AuthenticationException("secret"), "LLM_AUTH_FAILED");
        assertCode(new dev.langchain4j.exception.InvalidRequestException("secret"), "LLM_BAD_REQUEST");
        assertCode(new dev.langchain4j.exception.ModelNotFoundException("secret"), "LLM_NOT_FOUND");
        assertCode(new dev.langchain4j.exception.RateLimitException("secret"), "LLM_BUSY");
        assertCode(new dev.langchain4j.exception.InternalServerException("secret"), "LLM_HTTP_ERROR");
    }

    @Test void transportFailuresRemainDistinctAndPreserveExplicitDomainFailures() {
        assertCode(new java.net.UnknownHostException("secret"), "LLM_DNS_FAILED");
        assertCode(new java.net.ConnectException("secret"), "LLM_CONNECT_FAILED");
        assertCode(new javax.net.ssl.SSLHandshakeException("secret"), "LLM_TLS_FAILED");
        assertCode(new java.io.IOException("secret"), "LLM_NETWORK_FAILED");
        var domain = new LlmCallException("AGENT_CANCELLED", "fixture", false, null);
        assertThat(CareerAgentService.classifyStreamFailure(new RuntimeException(domain))).isSameAs(domain);
    }

    private void assertCode(Throwable cause, String code) {
        var classified = (LlmCallException) CareerAgentService.classifyStreamFailure(new RuntimeException(cause));
        assertThat(classified.getCode()).isEqualTo(code);
        assertThat(classified.getMessage()).doesNotContain("secret");
    }
}

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
}

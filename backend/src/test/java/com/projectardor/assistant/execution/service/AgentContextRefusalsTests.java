package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentContextRefusalsTests {

    @Test void aRequestLevelRefusalThatNamesTheContextWindowIsWorthRecoveringFrom() {
        assertThat(AgentContextRefusals.isLengthRefusal(new dev.langchain4j.exception.HttpException(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}"))).isTrue();
        assertThat(AgentContextRefusals.isLengthRefusal(new dev.langchain4j.exception.HttpException(413,
                "prompt is too long"))).isTrue();
    }

    @Test void otherProviderFailuresAreNotSilentlyReclassifiedAsContextProblems() {
        assertThat(AgentContextRefusals.isLengthRefusal(
                new dev.langchain4j.exception.HttpException(429, "Too many requests"))).isFalse();
        assertThat(AgentContextRefusals.isLengthRefusal(
                new dev.langchain4j.exception.HttpException(500, "maximum context length exceeded"))).isFalse();
        assertThat(AgentContextRefusals.isLengthRefusal(
                new dev.langchain4j.exception.HttpException(400, "Invalid model name"))).isFalse();
        assertThat(AgentContextRefusals.isLengthRefusal(new IllegalStateException("read timed out"))).isFalse();
    }

    @Test void aWrappedRefusalIsStillRecognisedButTheChainIsNotFollowedForever() {
        var wrapped = new RuntimeException("gateway", new RuntimeException("provider",
                new dev.langchain4j.exception.HttpException(400, "reduce the length of the input")));

        assertThat(AgentContextRefusals.isLengthRefusal(wrapped)).isTrue();
        assertThat(AgentContextRefusals.isLengthRefusal(new RuntimeException("cycle-safe", null))).isFalse();
    }

    @Test void aSelfIssuedContextLimitIsNotTreatedAsAnUpstreamRefusal() {
        // Our own fence already refused; retrying it would send the same request again.
        assertThat(AgentContextRefusals.isLengthRefusal(
                new com.projectardor.assistant.execution.service.AgentContextLimitException())).isFalse();
    }
}

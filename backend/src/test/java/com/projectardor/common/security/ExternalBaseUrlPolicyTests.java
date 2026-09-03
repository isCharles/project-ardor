package com.projectardor.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ExternalBaseUrlPolicyTests {

    private final ExternalBaseUrlPolicy policy = new ExternalBaseUrlPolicy(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080",
            "http://10.0.0.1",
            "http://169.254.169.254",
            "http://[::1]",
            "http://postgres:5432",
            "http://localhost"
    })
    void rejectsNonPublicDestinations(String url) {
        assertThatThrownBy(() -> policy.normalizeAndValidate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.openai.com/", "https://api.anthropic.com"})
    void acceptsPublicDestinations(String url) {
        assertThat(policy.normalizeAndValidate(url)).doesNotEndWith("/");
    }

    @Test
    void reportsUnresolvedPublicHostAsRetryableResolutionFailure() {
        assertThatThrownBy(() -> policy.normalizeAndValidate("https://definitely-unresolvable.invalid"))
                .isInstanceOf(ExternalHostResolutionException.class)
                .hasMessage("Base URL 的主机暂时无法解析，请稍后重试")
                .hasCauseInstanceOf(java.net.UnknownHostException.class);
    }
}

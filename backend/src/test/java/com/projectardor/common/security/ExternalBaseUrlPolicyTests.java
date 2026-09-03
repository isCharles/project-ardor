package com.projectardor.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}

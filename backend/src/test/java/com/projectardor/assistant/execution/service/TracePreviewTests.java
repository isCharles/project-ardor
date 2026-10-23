package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TracePreviewTests {
    @Test void redactsQuotedJsonSecretsBeforeTruncating() {
        String preview = TracePreview.of("{\"apiKey\":\"synthetic-secret\",\"password\":\"not real\",\"safe\":\"hello\"}");
        assertThat(preview).contains("[redacted]", "hello").doesNotContain("synthetic-secret", "not real");
    }
    @Test void redactsAuthorizationAndBareBearerTokens() {
        assertThat(TracePreview.of("Authorization: Bearer fake-token-value token=another-fixture"))
                .doesNotContain("fake-token-value", "another-fixture");
        assertThat(TracePreview.of("Bearer fixture.abc.def")).isEqualTo("Bearer [redacted]");
    }
    @Test void boundsEmptyAndLongPreviews() {
        assertThat(TracePreview.of("abc", 0)).isEmpty();
        assertThat(TracePreview.of("x".repeat(2000))).hasSize(900).endsWith("…");
    }
}

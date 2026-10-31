package com.projectardor.platform.model.speech.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RealtimeSpeechWebSocketConfigTests {

    @Test
    void parsesACommaSeparatedPublicOriginList() {
        assertThat(RealtimeSpeechWebSocketConfig.parseOrigins(
                " https://app.example.com , http://localhost:3000 ,,https://app.example.com "))
                .containsExactly("https://app.example.com", "http://localhost:3000");
    }

    @Test
    void blankConfigurationFallsBackToLoopbackDevelopmentOrigins() {
        assertThat(RealtimeSpeechWebSocketConfig.parseOrigins("  "))
                .containsExactly("http://localhost:3000", "http://127.0.0.1:3000");
        assertThat(RealtimeSpeechWebSocketConfig.parseOrigins(null))
                .containsExactly("http://localhost:3000", "http://127.0.0.1:3000");
    }
}

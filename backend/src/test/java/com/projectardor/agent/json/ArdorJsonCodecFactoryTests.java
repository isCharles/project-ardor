package com.projectardor.agent.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.langchain4j.internal.Json;

class ArdorJsonCodecFactoryTests {

    @Test
    void serializesInstantAsIso8601InsteadOfEpochNumber() {
        String json = Json.toJson(new TimedValue(Instant.parse("2026-09-03T08:30:00Z")));

        assertThat(json).contains("2026-09-03T08:30:00Z");
    }

    private record TimedValue(Instant createdAt) {
    }
}

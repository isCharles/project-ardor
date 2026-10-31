package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class AgentTraceStoreTests {

    @Test
    void oversizedAttributesAreDroppedInsteadOfPartiallyLeakingTheirContent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new AgentTraceStore(jdbc, JsonMapper.builder().build(),
                new SimpleMeterRegistry(), true, 30);
        String syntheticSecret = "sk-" + "a".repeat(5_000);

        store.startSpan(UUID.randomUUID(), UUID.randomUUID(), null, "TOOL", "create_calendar_task",
                Map.of("argumentsPreview", syntheticSecret));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Object[]> arguments = (ArgumentCaptor) ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        String storedAttributes = java.util.Arrays.stream(arguments.getValue())
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(value -> value.contains("truncated")).findFirst().orElseThrow();
        assertThat(storedAttributes).contains("\"truncated\":true").doesNotContain("aaaaaaaa");
    }
}

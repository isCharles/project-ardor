package com.projectardor.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.projectardor.assistant.execution.service.AgentDeletionConfirmations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@TestConfiguration
public class SecurityFilterTestConfig {
    @Bean
    AgentDeletionConfirmations deletionConfirmations() {
        return new AgentDeletionConfirmations();
    }

    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }
}

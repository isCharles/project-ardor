package com.projectardor.assistant.conversation.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.ReasoningDepth;

import jakarta.validation.Validation;

class AgentMessageRequestTests {
    private final jakarta.validation.Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static AgentMessageRequest request(String message, List<UUID> imageIds) {
        return new AgentMessageRequest(UUID.randomUUID(), message, null, null, List.of(), UUID.randomUUID(),
                ReasoningDepth.AUTO, null, imageIds, null);
    }

    @Test void blankMessageNeedsAnImageAndKeepsTheFieldError() {
        assertThat(validator.validate(request("   ", List.of())))
                .singleElement().satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("message");
                    assertThat(violation.getMessage()).isEqualTo("消息不能为空");
                });
        assertThat(validator.validate(request(null, List.of()))).hasSize(1);
        assertThat(validator.validate(request("   ", List.of(UUID.randomUUID())))).isEmpty();
        assertThat(validator.validate(request("你好", List.of()))).isEmpty();
    }

    @Test void rejectsMoreThanFourImages() {
        var tooMany = java.util.stream.IntStream.range(0, 5)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        assertThat(validator.validate(request("", tooMany))).anySatisfy(violation ->
                assertThat(violation.getPropertyPath().toString()).isEqualTo("imageIds"));
    }
}

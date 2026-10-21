package com.projectardor.assistant.conversation.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgentContextReferenceRequest(
        @NotBlank String type,
        @NotNull UUID id) {
}

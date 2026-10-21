package com.projectardor.assistant.memory.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentMemoryItemUpdateRequest(@NotBlank @Size(max = 1000) String content) {}

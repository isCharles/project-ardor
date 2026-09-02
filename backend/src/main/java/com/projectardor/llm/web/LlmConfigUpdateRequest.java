package com.projectardor.llm.web;

import com.projectardor.llm.domain.LlmProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LlmConfigUpdateRequest(
        @NotNull LlmProvider provider,
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 160) String model,
        @Size(max = 4096) String apiKey) {
}

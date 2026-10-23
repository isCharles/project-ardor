package com.projectardor.platform.model.llm.web;

import com.projectardor.platform.model.llm.domain.LlmProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Discovers model IDs for a personal LLM endpoint without changing the saved configuration. */
public record LlmModelListRequest(
        @NotNull LlmProvider provider,
        @NotBlank @Size(max = 512) String baseUrl,
        @Size(max = 4096) String apiKey) {
}

package com.projectardor.interview.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RunCodeRequest(
        @NotNull UUID questionId,
        @NotBlank @Size(max = 20_000) String code,
        @Size(max = 8_000) String stdin) {
}

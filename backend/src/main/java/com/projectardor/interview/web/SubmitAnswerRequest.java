package com.projectardor.interview.web;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitAnswerRequest(
        @NotNull UUID questionId,
        @NotBlank @Size(max = 20_000) String answerText,
        @Min(0) Integer durationSeconds) {
}

package com.projectardor.learning.practice.mockinterview.web;

import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitAdaptiveAnswerRequest(
        @NotNull UUID questionId,
        @NotBlank @Size(max = 20_000) String answerText,
        @Min(0) Integer durationSeconds,
        DifficultyFeedback difficultyFeedback) {
}
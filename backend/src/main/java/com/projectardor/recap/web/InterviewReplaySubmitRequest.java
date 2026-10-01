package com.projectardor.recap.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InterviewReplaySubmitRequest(@NotNull UUID requestId,
        @NotBlank @Size(max = 12000) String answer) {}

package com.projectardor.learning.practice.question.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

public record InterviewRetestRequest(@NotNull UUID attemptId, @NotNull @Future Instant dueAt) {}

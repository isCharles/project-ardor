package com.projectardor.learning.web;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Synchronous grading request. Version fences are optional for old clients; when supplied
 * they reject a stale answer set before any model call with the same 409 error as the
 * asynchronous evaluation path.
 */
public record LearningAttemptRequest(
        @NotEmpty @Size(max = 8) List<@Size(max = 12000) String> answers,
        @Min(0) Integer expectedAttemptCount,
        @Min(1) Integer exercisesVersion) {
}

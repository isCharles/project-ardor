package com.projectardor.learning.web;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record LearningAttemptRequest(
        @NotEmpty @Size(max = 8) List<@Size(max = 12000) String> answers) {
}

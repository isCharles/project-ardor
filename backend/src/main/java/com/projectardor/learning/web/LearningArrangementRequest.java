package com.projectardor.learning.web;

import java.time.Instant;
import jakarta.validation.constraints.Size;

/** Null leaves a field unchanged; an empty reason clears only the reason. */
public record LearningArrangementRequest(@Size(max = 4000) String reason, Instant scheduledAt) {}

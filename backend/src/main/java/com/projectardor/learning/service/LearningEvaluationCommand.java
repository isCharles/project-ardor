package com.projectardor.learning.service;

import java.util.List;
import java.util.UUID;

public record LearningEvaluationCommand(UUID requestId, Integer expectedAttemptCount, List<String> answers) {
    public LearningEvaluationCommand { if (answers != null) answers=List.copyOf(answers); }
}

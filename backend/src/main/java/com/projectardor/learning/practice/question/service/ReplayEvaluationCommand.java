package com.projectardor.learning.practice.question.service;

import java.util.UUID;

public record ReplayEvaluationCommand(UUID requestId, String answer, UUID retestTaskId) {}

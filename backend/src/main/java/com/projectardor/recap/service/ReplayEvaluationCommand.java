package com.projectardor.recap.service;

import java.util.UUID;

public record ReplayEvaluationCommand(UUID requestId, String answer, UUID retestTaskId) {}

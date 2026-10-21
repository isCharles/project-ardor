package com.projectardor.learning.plan.web;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import com.projectardor.platform.usage.QuotaRequestId;

public record LearningEvaluationQueueRequest(@NotNull UUID requestId,
        @NotNull @Min(0) Integer expectedAttemptCount,
        @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 12000) String> answers) implements QuotaRequestId {
    public com.projectardor.learning.practice.exercises.service.LearningEvaluationCommand command() {
        return new com.projectardor.learning.practice.exercises.service.LearningEvaluationCommand(requestId,expectedAttemptCount,answers);
    }
}

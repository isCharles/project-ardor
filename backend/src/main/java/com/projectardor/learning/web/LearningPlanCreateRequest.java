package com.projectardor.learning.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.usage.QuotaRequestId;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningPlanCreateRequest(
        @NotBlank @Size(max = 160) String concept,
        @Size(max = 4000) String reason,
        LearningSourceType sourceType,
        UUID sourceId,
        Instant scheduledAt,
        UUID requestId) implements QuotaRequestId {
}

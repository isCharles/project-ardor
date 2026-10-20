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
        UUID requestId,
        com.projectardor.learning.domain.LearningOriginKind originKind,
        UUID originResourceId, UUID targetInterviewId, UUID targetRoundId) implements QuotaRequestId {
    public LearningPlanCreateRequest(String concept, String reason, LearningSourceType sourceType, UUID sourceId,
            Instant scheduledAt, UUID requestId) {
        this(concept,reason,sourceType,sourceId,scheduledAt,requestId,null,null,null,null);
    }
    public com.projectardor.learning.service.LearningContextInput context() {
        return com.projectardor.learning.service.LearningContextInput.optional(originKind,originResourceId,targetInterviewId,targetRoundId);
    }
}

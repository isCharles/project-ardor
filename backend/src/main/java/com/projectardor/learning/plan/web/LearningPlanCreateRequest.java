package com.projectardor.learning.plan.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.platform.usage.QuotaRequestId;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LearningPlanCreateRequest(
        @NotBlank @Size(max = 160) String concept,
        @Size(max = 4000) String reason,
        LearningSourceType sourceType,
        UUID sourceId,
        Instant scheduledAt,
        UUID requestId,
        @JsonInclude(JsonInclude.Include.NON_NULL) com.projectardor.learning.plan.domain.LearningOriginKind originKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID originResourceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID targetInterviewId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID targetRoundId) implements QuotaRequestId {
    public LearningPlanCreateRequest(String concept, String reason, LearningSourceType sourceType, UUID sourceId,
            Instant scheduledAt, UUID requestId) {
        this(concept,reason,sourceType,sourceId,scheduledAt,requestId,null,null,null,null);
    }
    public com.projectardor.learning.plan.service.LearningContextInput context() {
        return com.projectardor.learning.plan.service.LearningContextInput.optional(originKind,originResourceId,targetInterviewId,targetRoundId);
    }
    public com.projectardor.learning.plan.service.LearningCreationCommand command() {
        return new com.projectardor.learning.plan.service.LearningCreationCommand(concept,reason,sourceType,sourceId,scheduledAt,requestId,
                originKind,originResourceId,targetInterviewId,targetRoundId);
    }
}

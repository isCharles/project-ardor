package com.projectardor.learning.service;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.domain.LearningSourceType;

/** Application command. Field order and null inclusion preserve existing queued payload hashes. */
public record LearningCreationCommand(String concept, String reason, LearningSourceType sourceType, UUID sourceId,
        Instant scheduledAt, UUID requestId,
        @JsonInclude(JsonInclude.Include.NON_NULL) LearningOriginKind originKind,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID originResourceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID targetInterviewId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID targetRoundId) {
    public LearningContextInput context() { return LearningContextInput.optional(originKind,originResourceId,targetInterviewId,targetRoundId); }
}

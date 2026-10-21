package com.projectardor.learning.plan.service;

import java.util.UUID;
import com.projectardor.learning.plan.domain.LearningOriginKind;

/** Source and preparation target are independent, optional dimensions. */
public record LearningContextInput(LearningOriginKind originKind, UUID originResourceId,
        UUID targetInterviewId, UUID targetRoundId) {
    public String signature() {
        return String.valueOf(originKind) + ":" + originResourceId + ":" + targetInterviewId + ":" + targetRoundId;
    }
    public static LearningContextInput optional(LearningOriginKind kind, UUID origin, UUID interview, UUID round) {
        return kind == null && origin == null && interview == null && round == null
                ? null : new LearningContextInput(kind, origin, interview, round);
    }
}

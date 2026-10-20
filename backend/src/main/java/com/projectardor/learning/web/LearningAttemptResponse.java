package com.projectardor.learning.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.domain.LearningAttempt;

/** Read-only immutable attempt history for one owned learning plan. */
public record LearningAttemptResponse(
        UUID id,
        UUID planId,
        int attemptNo,
        UUID requestId,
        List<Map<String, Object>> exercises,
        List<String> answers,
        Integer score,
        Map<String, Object> evaluation,
        String modelName,
        String promptVersion,
        boolean legacy,
        Instant submittedAt) {

    public static LearningAttemptResponse from(LearningAttempt attempt) {
        return new LearningAttemptResponse(attempt.getId(), attempt.getPlanId(), attempt.getAttemptNo(),
                attempt.getRequestId(), attempt.getExerciseSnapshot(), attempt.getAnswers(), attempt.getScore(),
                attempt.getEvaluation(), attempt.getModelName(), attempt.getPromptVersion(),
                attempt.isLegacy(), attempt.getSubmittedAt());
    }
}

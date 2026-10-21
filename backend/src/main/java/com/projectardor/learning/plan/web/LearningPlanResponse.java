package com.projectardor.learning.plan.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.domain.LearningStatus;

public record LearningPlanResponse(
        UUID id,
        UUID requestId,
        String concept,
        String reason,
        LearningSourceType sourceType,
        UUID sourceId,
        LearningStatus status,
        Map<String, Object> lesson,
        List<ExerciseResponse> exercises,
        Map<String, Object> lastEvaluation,
        Integer lastScore,
        int attemptCount,
        Instant scheduledAt,
        Instant nextReviewAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        com.projectardor.learning.plan.domain.LearningOriginKind originKind,
        UUID originResourceId, UUID originRecordId, UUID targetInterviewId, UUID targetRoundId,
        int contentVersion,
        int exercisesVersion) {

    public static LearningPlanResponse from(LearningPlan plan) {
        List<ExerciseResponse> exercises = plan.getExercises().stream()
                .map(item -> new ExerciseResponse(
                        item.get("exerciseId") == null ? null : String.valueOf(item.get("exerciseId")),
                        String.valueOf(item.get("question")),
                        String.valueOf(item.getOrDefault("type", "SHORT_ANSWER")),
                        item.get("options") instanceof List<?> options
                                ? options.stream().map(String::valueOf).toList() : List.of()))
                .toList();
        return new LearningPlanResponse(plan.getId(), plan.getRequestId(), plan.getConcept(), plan.getReason(),
                plan.getSourceType(), plan.getSourceId(), plan.getStatus(), plan.getLesson(),
                exercises, plan.getLastEvaluation(), plan.getLastScore(), plan.getAttemptCount(),
                plan.getScheduledAt(), plan.getNextReviewAt(), plan.getCompletedAt(),
                plan.getCreatedAt(), plan.getUpdatedAt(), plan.getOriginKind() != null ? plan.getOriginKind()
                    : plan.getSourceType() == LearningSourceType.RECAP ? com.projectardor.learning.plan.domain.LearningOriginKind.LEGACY_RECAP
                    : com.projectardor.learning.plan.domain.LearningOriginKind.valueOf(plan.getSourceType().name()),
                plan.getOriginResourceId(),plan.getOriginRecordId(),plan.getTargetInterviewId(),plan.getTargetRoundId(),
                plan.getContentVersion(), plan.getExercisesVersion());
    }

    public record ExerciseResponse(String exerciseId, String question, String type, List<String> options) {}
}

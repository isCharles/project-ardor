package com.projectardor.learning.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.domain.LearningStatus;

public record LearningPlanResponse(
        UUID id,
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
        Instant updatedAt) {

    public static LearningPlanResponse from(LearningPlan plan) {
        List<ExerciseResponse> exercises = plan.getExercises().stream()
                .map(item -> new ExerciseResponse(String.valueOf(item.get("question"))))
                .toList();
        return new LearningPlanResponse(plan.getId(), plan.getConcept(), plan.getReason(),
                plan.getSourceType(), plan.getSourceId(), plan.getStatus(), plan.getLesson(),
                exercises, plan.getLastEvaluation(), plan.getLastScore(), plan.getAttemptCount(),
                plan.getScheduledAt(), plan.getNextReviewAt(), plan.getCompletedAt(),
                plan.getCreatedAt(), plan.getUpdatedAt());
    }

    public record ExerciseResponse(String question) {}
}

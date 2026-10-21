package com.projectardor.learning.plan.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.domain.LearningStatus;
import com.projectardor.learning.plan.service.LearningPlanView;

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
        return from(LearningPlanView.from(plan));
    }

    public static LearningPlanResponse from(LearningPlanView view) {
        List<ExerciseResponse> exercises = view.exercises() == null ? null : view.exercises().stream()
                .map(item -> new ExerciseResponse(item.exerciseId(), item.question(), item.type(), item.options())).toList();
        return new LearningPlanResponse(view.id(), view.requestId(), view.concept(), view.reason(),
                view.sourceType(), view.sourceId(), view.status(), view.lesson(), exercises,
                view.lastEvaluation(), view.lastScore(), view.attemptCount(), view.scheduledAt(),
                view.nextReviewAt(), view.completedAt(), view.createdAt(), view.updatedAt(),
                view.originKind(), view.originResourceId(), view.originRecordId(), view.targetInterviewId(),
                view.targetRoundId(), view.contentVersion(), view.exercisesVersion());
    }

    public record ExerciseResponse(String exerciseId, String question, String type, List<String> options) {}
}

package com.projectardor.learning.plan.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.projectardor.learning.plan.domain.LearningOriginKind;
import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.domain.LearningStatus;

/** Owned application snapshot shared by HTTP and Agent; never a managed entity or Web DTO. */
public record LearningPlanView(
        UUID id, UUID requestId, String concept, String reason,
        LearningSourceType sourceType, UUID sourceId, LearningStatus status,
        Map<String, Object> lesson, List<Exercise> exercises,
        Map<String, Object> lastEvaluation, Integer lastScore, int attemptCount,
        Instant scheduledAt, Instant nextReviewAt, Instant completedAt, Instant createdAt, Instant updatedAt,
        LearningOriginKind originKind, UUID originResourceId, UUID originRecordId,
        UUID targetInterviewId, UUID targetRoundId, int contentVersion, int exercisesVersion) {

    public LearningPlanView {
        lesson = snapshotMap(lesson);
        lastEvaluation = snapshotMap(lastEvaluation);
        exercises = exercises == null ? null : Collections.unmodifiableList(new ArrayList<>(exercises));
    }

    public static LearningPlanView from(LearningPlan plan) {
        List<Exercise> exercises = plan.getExercises().stream()
                .map(item -> new Exercise(
                        item.get("exerciseId") == null ? null : String.valueOf(item.get("exerciseId")),
                        String.valueOf(item.get("question")),
                        String.valueOf(item.getOrDefault("type", "SHORT_ANSWER")),
                        item.get("options") instanceof List<?> options
                                ? options.stream().map(String::valueOf).toList() : List.of()))
                .toList();
        return new LearningPlanView(plan.getId(), plan.getRequestId(), plan.getConcept(), plan.getReason(),
                plan.getSourceType(), plan.getSourceId(), plan.getStatus(), plan.getLesson(), exercises,
                plan.getLastEvaluation(), plan.getLastScore(), plan.getAttemptCount(),
                plan.getScheduledAt(), plan.getNextReviewAt(), plan.getCompletedAt(), plan.getCreatedAt(), plan.getUpdatedAt(),
                plan.getOriginKind() != null ? plan.getOriginKind()
                        : plan.getSourceType() == LearningSourceType.RECAP ? LearningOriginKind.LEGACY_RECAP
                        : LearningOriginKind.valueOf(plan.getSourceType().name()),
                plan.getOriginResourceId(), plan.getOriginRecordId(), plan.getTargetInterviewId(), plan.getTargetRoundId(),
                plan.getContentVersion(), plan.getExercisesVersion());
    }

    public record Exercise(String exerciseId, String question, String type, List<String> options) {
        public Exercise {
            options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
        }
    }

    private static Map<String, Object> snapshotMap(Map<String, Object> source) {
        if (source == null) return null;
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(key, snapshot(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshot(Object value) {
        if (value instanceof Map<?, ?> source) {
            var copy = new LinkedHashMap<Object, Object>();
            source.forEach((key, item) -> copy.put(key, snapshot(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            var copy = new ArrayList<Object>();
            source.forEach(item -> copy.add(snapshot(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}

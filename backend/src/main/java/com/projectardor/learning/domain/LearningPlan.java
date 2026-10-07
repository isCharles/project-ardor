package com.projectardor.learning.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "learning_plans")
public class LearningPlan {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "request_id") private UUID requestId;
    @Column(name = "request_hash", length = 64) private String requestHash;
    @Column(nullable = false, length = 160) private String concept;
    @Column(columnDefinition = "text") private String reason;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private LearningSourceType sourceType;
    @Column(name = "source_id") private UUID sourceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private LearningStatus status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> lesson = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<Map<String, Object>> exercises = List.of();
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "last_evaluation", nullable = false, columnDefinition = "jsonb") private Map<String, Object> lastEvaluation = new LinkedHashMap<>();
    @Column(name = "last_score") private Integer lastScore;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "scheduled_at", nullable = false) private Instant scheduledAt;
    @Column(name = "next_review_at") private Instant nextReviewAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected LearningPlan() {}

    private LearningPlan(UUID userId, String concept, String reason, LearningSourceType sourceType,
            UUID sourceId, Map<String, Object> lesson, List<Map<String, Object>> exercises,
            Instant scheduledAt, String modelName) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.concept = concept;
        this.reason = reason;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.lesson = new LinkedHashMap<>(lesson);
        this.exercises = List.copyOf(exercises);
        this.scheduledAt = scheduledAt;
        this.modelName = modelName;
        this.status = LearningStatus.SCHEDULED;
    }

    public static LearningPlan create(UUID userId, String concept, String reason, LearningSourceType sourceType,
            UUID sourceId, Map<String, Object> lesson, List<Map<String, Object>> exercises,
            Instant scheduledAt, String modelName) {
        return new LearningPlan(userId, concept, reason, sourceType, sourceId, lesson, exercises, scheduledAt, modelName);
    }

    public static LearningPlan create(UUID userId, UUID requestId, String requestHash, String concept, String reason,
            LearningSourceType sourceType, UUID sourceId, Map<String, Object> lesson,
            List<Map<String, Object>> exercises, Instant scheduledAt, String modelName) {
        LearningPlan plan = create(userId, concept, reason, sourceType, sourceId, lesson, exercises, scheduledAt, modelName);
        plan.requestId = requestId;
        plan.requestHash = requestHash;
        return plan;
    }

    public void markInProgress() {
        if (status != LearningStatus.COMPLETED) status = LearningStatus.IN_PROGRESS;
    }

    public void recordAttempt(int score, Map<String, Object> evaluation, Instant nextReviewAt) {
        recordAttempt(score, evaluation, nextReviewAt, List.of());
    }

    public void recordAttempt(int score, Map<String, Object> evaluation, Instant nextReviewAt,
            List<Map<String, Object>> followUpExercises) {
        this.lastScore = score;
        this.lastEvaluation = new LinkedHashMap<>(evaluation);
        this.attemptCount++;
        if (score >= 80) {
            this.status = LearningStatus.COMPLETED;
            this.completedAt = Instant.now();
            this.nextReviewAt = null;
        } else {
            this.status = LearningStatus.NEEDS_REVIEW;
            this.nextReviewAt = nextReviewAt;
            this.scheduledAt = nextReviewAt;
            this.completedAt = null;
            if (followUpExercises != null && !followUpExercises.isEmpty()) {
                this.exercises = List.copyOf(followUpExercises);
            }
        }
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRequestId() { return requestId; }
    public String getRequestHash() { return requestHash; }
    public String getConcept() { return concept; }
    public String getReason() { return reason; }
    public LearningSourceType getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public LearningStatus getStatus() { return status; }
    public Map<String, Object> getLesson() { return Map.copyOf(lesson); }
    public List<Map<String, Object>> getExercises() { return List.copyOf(exercises); }
    public Map<String, Object> getLastEvaluation() { return Map.copyOf(lastEvaluation); }
    public Integer getLastScore() { return lastScore; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getNextReviewAt() { return nextReviewAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getModelName() { return modelName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

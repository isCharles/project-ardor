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
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One immutable graded learning round. Rows written by V52 backfill come from the old
 * {@code last_evaluation.attempts} projection and stay {@code legacy = true}: they do not
 * invent a rubric, per-round feedback sections or a grading model that was never stored.
 */
@Entity
@Table(name = "learning_attempts")
public class LearningAttempt {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "plan_id", nullable = false) private UUID planId;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(name = "request_id") private UUID requestId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "exercise_snapshot", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> exerciseSnapshot = List.of();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> answers = List.of();
    @Column private Integer score;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evaluation = new LinkedHashMap<>();
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", length = 40) private String promptVersion;
    @Column(nullable = false) private boolean legacy;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected LearningAttempt() {}

    private LearningAttempt(UUID userId, UUID planId, int attemptNo, UUID requestId,
            List<Map<String, Object>> exerciseSnapshot, List<String> answers, int score,
            Map<String, Object> evaluation, String modelName, String promptVersion,
            boolean legacy, Instant submittedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.planId = planId;
        this.attemptNo = attemptNo;
        this.requestId = requestId;
        List<Map<String, Object>> snapshot = new java.util.ArrayList<>();
        for (Map<String, Object> item : exerciseSnapshot) snapshot.add(new LinkedHashMap<>(item));
        this.exerciseSnapshot = List.copyOf(snapshot);
        this.answers = List.copyOf(answers);
        this.score = score;
        this.evaluation = new LinkedHashMap<>(evaluation);
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.legacy = legacy;
        this.submittedAt = submittedAt;
    }

    /** Records a round graded by the current evaluation prompt. */
    public static LearningAttempt graded(UUID userId, UUID planId, int attemptNo, UUID requestId,
            List<Map<String, Object>> exerciseSnapshot, List<String> answers, int score,
            Map<String, Object> evaluation, String modelName, String promptVersion, Instant submittedAt) {
        return new LearningAttempt(userId, planId, attemptNo, requestId, exerciseSnapshot, answers, score,
                evaluation, modelName, promptVersion, false, submittedAt);
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getPlanId() { return planId; }
    public int getAttemptNo() { return attemptNo; }
    public UUID getRequestId() { return requestId; }
    public List<Map<String, Object>> getExerciseSnapshot() { return List.copyOf(exerciseSnapshot); }
    public List<String> getAnswers() { return List.copyOf(answers); }
    public Integer getScore() { return score; }
    public Map<String, Object> getEvaluation() { return Map.copyOf(evaluation); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public boolean isLegacy() { return legacy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

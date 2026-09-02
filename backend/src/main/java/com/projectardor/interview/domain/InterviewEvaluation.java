package com.projectardor.interview.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_evaluations")
public class InterviewEvaluation {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false, unique = true) private UUID interviewSessionId;
    @Column(name = "overall_score", nullable = false, precision = 5, scale = 2) private BigDecimal overallScore;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evaluation = new LinkedHashMap<>();
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewEvaluation() {}

    private InterviewEvaluation(
            UUID userId, UUID sessionId, BigDecimal score,
            Map<String, Object> evaluation, String modelName) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = sessionId;
        this.overallScore = score;
        this.evaluation = new LinkedHashMap<>(evaluation);
        this.modelName = modelName;
        this.promptVersion = "interview-evaluation-v1";
    }

    public static InterviewEvaluation create(
            UUID userId, UUID sessionId, BigDecimal score,
            Map<String, Object> evaluation, String modelName) {
        return new InterviewEvaluation(userId, sessionId, score, evaluation, modelName);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public BigDecimal getOverallScore() { return overallScore; }
    public Map<String, Object> getEvaluation() { return Map.copyOf(evaluation); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.projectardor.learning.practice.mockinterview.domain;

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
@Table(name = "interview_plans")
public class InterviewPlan {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false, unique = true) private UUID interviewSessionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> plan = new LinkedHashMap<>();
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewPlan() {}

    private InterviewPlan(UUID userId, UUID sessionId, Map<String, Object> plan,
            String modelName, String promptVersion) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = sessionId;
        this.plan = new LinkedHashMap<>(plan);
        this.modelName = modelName;
        this.promptVersion = promptVersion;
    }

    public static InterviewPlan create(UUID userId, UUID sessionId, Map<String, Object> plan,
            String modelName, String promptVersion) {
        return new InterviewPlan(userId, sessionId, plan, modelName, promptVersion);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public Map<String, Object> getPlan() { return Map.copyOf(plan); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
package com.projectardor.resume.domain;

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
@Table(name = "resume_analyses")
public class ResumeAnalysis {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "resume_id", nullable = false)
    private UUID resumeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> analysis = new LinkedHashMap<>();

    @Column(name = "model_name", length = 120)
    private String modelName;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ResumeAnalysis() {
    }

    private ResumeAnalysis(UUID userId, UUID resumeId, Map<String, Object> analysis, String modelName) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.resumeId = resumeId;
        this.analysis = new LinkedHashMap<>(analysis);
        this.modelName = modelName;
        this.promptVersion = "resume-analysis-v2";
    }

    public static ResumeAnalysis create(
            UUID userId,
            UUID resumeId,
            Map<String, Object> analysis,
            String modelName) {
        return new ResumeAnalysis(userId, resumeId, analysis, modelName);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResumeId() { return resumeId; }
    public Map<String, Object> getAnalysis() { return Map.copyOf(analysis); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}

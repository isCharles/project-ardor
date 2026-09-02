package com.projectardor.recap.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

@Entity
@Table(name = "interview_recaps")
public class InterviewRecap {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 240) private String title;
    @Column(length = 160) private String company;
    @Column(name = "target_role", length = 160) private String targetRole;
    @Column(name = "occurred_at") private Instant occurredAt;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private InterviewRecapSource sourceType;
    @Column(name = "raw_content", nullable = false, columnDefinition = "text") private String rawContent;
    @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
    @Column(nullable = false, columnDefinition = "text") private String overview;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> strengths = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> weaknesses = new ArrayList<>();
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewRecap() {}

    private InterviewRecap(UUID userId, String title, String company, String targetRole, Instant occurredAt,
            InterviewRecapSource sourceType, String rawContent, String inputHash, String overview,
            List<String> strengths, List<String> weaknesses, String modelName) {
        this.id = UUID.randomUUID(); this.userId = userId; this.title = title; this.company = company;
        this.targetRole = targetRole; this.occurredAt = occurredAt; this.sourceType = sourceType;
        this.rawContent = rawContent; this.inputHash = inputHash; this.overview = overview;
        this.strengths = new ArrayList<>(strengths); this.weaknesses = new ArrayList<>(weaknesses);
        this.modelName = modelName; this.promptVersion = "interview-recap-v1";
    }

    public static InterviewRecap create(UUID userId, String title, String company, String targetRole, Instant occurredAt,
            InterviewRecapSource sourceType, String rawContent, String inputHash, String overview,
            List<String> strengths, List<String> weaknesses, String modelName) {
        return new InterviewRecap(userId, title, company, targetRole, occurredAt, sourceType, rawContent,
                inputHash, overview, strengths, weaknesses, modelName);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getCompany() { return company; }
    public String getTargetRole() { return targetRole; }
    public Instant getOccurredAt() { return occurredAt; }
    public InterviewRecapSource getSourceType() { return sourceType; }
    public String getRawContent() { return rawContent; }
    public String getOverview() { return overview; }
    public List<String> getStrengths() { return List.copyOf(strengths); }
    public List<String> getWeaknesses() { return List.copyOf(weaknesses); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}

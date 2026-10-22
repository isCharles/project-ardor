package com.projectardor.jobsearch.record.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

/**
 * One interview's written debrief. The process owns the company, role and date;
 * a recap only points at its interview and carries the document itself.
 */
@Entity
@Table(name = "interview_recaps")
public class InterviewRecap {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 240) private String title;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private InterviewRecapSource sourceType;
    @Column(name = "raw_content", nullable = false, columnDefinition = "text") private String rawContent;
    @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
    @Column(nullable = false, columnDefinition = "text") private String overview;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> strengths = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> weaknesses = new ArrayList<>();
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(name = "job_opportunity_id") private UUID jobOpportunityId;
    @Column(name = "interview_round_id") private UUID interviewRoundId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewRecap() {}

    private InterviewRecap(UUID userId, String title, InterviewRecapSource sourceType, String rawContent,
            String inputHash, String overview, List<String> strengths, List<String> weaknesses, String modelName,
            UUID jobOpportunityId) {
        this.id = UUID.randomUUID(); this.userId = userId; this.title = title; this.sourceType = sourceType;
        this.rawContent = rawContent; this.inputHash = inputHash; this.overview = overview;
        this.strengths = new ArrayList<>(strengths); this.weaknesses = new ArrayList<>(weaknesses);
        this.modelName = modelName; this.promptVersion = "interview-recap-v1";
        this.jobOpportunityId = jobOpportunityId;
    }

    public static InterviewRecap create(UUID userId, String title, InterviewRecapSource sourceType,
            String rawContent, String inputHash, String overview, List<String> strengths, List<String> weaknesses,
            String modelName, UUID jobOpportunityId) {
        return new InterviewRecap(userId, title, sourceType, rawContent, inputHash, overview, strengths, weaknesses,
                modelName, jobOpportunityId);
    }

    /** Attaches a process only when this recap is not linked yet; never moves it. */
    public boolean linkOpportunity(UUID opportunityId) {
        if (opportunityId == null || jobOpportunityId != null) return false;
        this.jobOpportunityId = opportunityId;
        return true;
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public void markConfirmedMinutes() { this.promptVersion = "confirmed-minutes-review-v1"; }
    public void markFinalMinutes() { this.promptVersion = "final-minutes-normalization-v1"; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public InterviewRecapSource getSourceType() { return sourceType; }
    public String getRawContent() { return rawContent; }
    public String getOverview() { return overview; }
    public List<String> getStrengths() { return List.copyOf(strengths); }
    public List<String> getWeaknesses() { return List.copyOf(weaknesses); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public UUID getJobOpportunityId() { return jobOpportunityId; }
    public UUID getInterviewRoundId() { return interviewRoundId; }
    public void linkRound(UUID roundId) {
        if (roundId != null && jobOpportunityId == null) throw new IllegalStateException("轮次须属于面试流程");
        if (interviewRoundId != null && !interviewRoundId.equals(roundId)) throw new IllegalStateException("不能改挂面试轮次");
        interviewRoundId = roundId;
    }
    public Instant getCreatedAt() { return createdAt; }
}

package com.projectardor.learning.practice.mockinterview.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_sessions")
public class InterviewSession {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_analysis_id") private UUID resumeAnalysisId;
    @Column(name = "job_opportunity_id") private UUID jobOpportunityId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private InterviewModality modality;
    @Column(name = "target_company", length = 160) private String targetCompany;
    @Column(name = "target_role", nullable = false, length = 160) private String targetRole;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private InterviewStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private InterviewMode mode;
    @Column(length = 64) private String seniority;
    @Column(name = "duration_minutes") private Integer durationMinutes;
    @Column(name = "deep_dive", nullable = false) private boolean deepDive;
    @Column(name = "job_description", columnDefinition = "text") private String jobDescription;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewSession() {}

    private InterviewSession(
            UUID userId,
            UUID resumeAnalysisId,
            InterviewModality modality,
            String targetCompany,
            String targetRole,
            UUID jobOpportunityId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.resumeAnalysisId = resumeAnalysisId;
        this.jobOpportunityId = jobOpportunityId;
        this.modality = modality;
        this.targetCompany = targetCompany;
        this.targetRole = targetRole;
        this.status = InterviewStatus.IN_PROGRESS;
        this.mode = InterviewMode.CLASSIC;
        this.startedAt = Instant.now();
    }

    public static InterviewSession create(
            UUID userId, UUID resumeAnalysisId, String targetCompany, String targetRole) {
        return create(userId, resumeAnalysisId, InterviewModality.TEXT, targetCompany, targetRole, null);
    }

    public static InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
            InterviewModality modality,
            String targetCompany,
            String targetRole) {
        return create(userId, resumeAnalysisId, modality, targetCompany, targetRole, null);
    }

    public static InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
            InterviewModality modality,
            String targetCompany,
            String targetRole,
            UUID jobOpportunityId) {
        if (modality == null) throw new IllegalArgumentException("面试方式不能为空");
        return new InterviewSession(userId, resumeAnalysisId, modality, targetCompany, targetRole, jobOpportunityId);
    }

    public static InterviewSession createAdaptive(
            UUID userId,
            UUID resumeAnalysisId,
            String targetCompany,
            String targetRole,
            UUID jobOpportunityId,
            String seniority,
            Integer durationMinutes,
            boolean deepDive,
            String jobDescription) {
        if (durationMinutes != null && durationMinutes < 1) {
            throw new IllegalArgumentException("面试时长必须为正数");
        }
        InterviewSession session = new InterviewSession(userId, resumeAnalysisId, InterviewModality.TEXT,
                targetCompany, targetRole, jobOpportunityId);
        session.mode = InterviewMode.ADAPTIVE;
        session.seniority = seniority;
        session.durationMinutes = durationMinutes;
        session.deepDive = deepDive;
        session.jobDescription = jobDescription;
        return session;
    }

    public void complete() {
        if (status != InterviewStatus.IN_PROGRESS) throw new IllegalStateException("当前面试不能结束");
        status = InterviewStatus.COMPLETED;
        finishedAt = Instant.now();
    }

    public void cancel() {
        if (status == InterviewStatus.COMPLETED || status == InterviewStatus.CANCELLED) {
            throw new IllegalStateException("当前面试不能取消");
        }
        status = InterviewStatus.CANCELLED;
        finishedAt = Instant.now();
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResumeAnalysisId() { return resumeAnalysisId; }
    public UUID getJobOpportunityId() { return jobOpportunityId; }
    public InterviewModality getModality() { return modality; }
    public String getTargetCompany() { return targetCompany; }
    public String getTargetRole() { return targetRole; }
    public InterviewStatus getStatus() { return status; }
    public InterviewMode getMode() { return mode; }
    public String getSeniority() { return seniority; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public boolean isDeepDive() { return deepDive; }
    public String getJobDescription() { return jobDescription; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

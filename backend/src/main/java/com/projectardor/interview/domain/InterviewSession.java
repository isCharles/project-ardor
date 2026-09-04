package com.projectardor.interview.domain;

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
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private InterviewModality modality;
    @Column(name = "target_company", length = 160) private String targetCompany;
    @Column(name = "target_role", nullable = false, length = 160) private String targetRole;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private InterviewStatus status;
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
            String targetRole) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.resumeAnalysisId = resumeAnalysisId;
        this.modality = modality;
        this.targetCompany = targetCompany;
        this.targetRole = targetRole;
        this.status = InterviewStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    public static InterviewSession create(
            UUID userId, UUID resumeAnalysisId, String targetCompany, String targetRole) {
        return create(userId, resumeAnalysisId, InterviewModality.TEXT, targetCompany, targetRole);
    }

    public static InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
            InterviewModality modality,
            String targetCompany,
            String targetRole) {
        if (modality == null) throw new IllegalArgumentException("面试方式不能为空");
        return new InterviewSession(userId, resumeAnalysisId, modality, targetCompany, targetRole);
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
    public InterviewModality getModality() { return modality; }
    public String getTargetCompany() { return targetCompany; }
    public String getTargetRole() { return targetRole; }
    public InterviewStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

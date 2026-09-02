package com.projectardor.resume.domain;

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
@Table(name = "resume_analysis_jobs")
public class ResumeAnalysisJob {

    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_id", nullable = false) private UUID resumeId;
    @Column(name = "analysis_id") private UUID analysisId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ResumeAnalysisJobStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ResumeAnalysisJob() {}

    private ResumeAnalysisJob(UUID userId, UUID resumeId) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.resumeId = resumeId;
        this.status = ResumeAnalysisJobStatus.QUEUED;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ResumeAnalysisJob create(UUID userId, UUID resumeId) {
        return new ResumeAnalysisJob(userId, resumeId);
    }

    public void complete(UUID analysisId) {
        this.analysisId = analysisId;
        this.status = ResumeAnalysisJobStatus.COMPLETED;
        this.finishedAt = Instant.now();
        this.errorCode = null;
        this.errorMessage = null;
    }

    public void fail(String code, String message) {
        this.status = ResumeAnalysisJobStatus.FAILED;
        this.finishedAt = Instant.now();
        this.errorCode = truncate(code, 64);
        this.errorMessage = truncate(message, 1000);
    }

    public void retry(String code, String message, Instant nextAttemptAt) {
        this.status = ResumeAnalysisJobStatus.QUEUED;
        this.availableAt = nextAttemptAt;
        this.startedAt = null;
        this.errorCode = truncate(code, 64);
        this.errorMessage = truncate(message, 1000);
    }

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getResumeId() { return resumeId; }
    public UUID getAnalysisId() { return analysisId; }
    public ResumeAnalysisJobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}

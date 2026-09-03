package com.projectardor.recap.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "interview_recap_jobs")
public class InterviewRecapJob {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "recap_id") private UUID recapId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
    @Column(name = "raw_content", nullable = false, columnDefinition = "text") private String rawContent;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private InterviewRecapJobStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewRecapJob() {}

    private InterviewRecapJob(UUID userId, String inputHash, String rawContent) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID(); this.userId = userId; this.inputHash = inputHash; this.rawContent = rawContent;
        this.status = InterviewRecapJobStatus.QUEUED; this.availableAt = now; this.createdAt = now; this.updatedAt = now;
    }

    public static InterviewRecapJob create(UUID userId, String inputHash, String rawContent) {
        return new InterviewRecapJob(userId, inputHash, rawContent);
    }

    public void complete(UUID recapId) {
        this.recapId = recapId; this.status = InterviewRecapJobStatus.COMPLETED; this.finishedAt = Instant.now();
        this.errorCode = null; this.errorMessage = null;
    }

    public void retry(String code, String message, Instant nextAttemptAt) {
        this.status = InterviewRecapJobStatus.QUEUED; this.availableAt = nextAttemptAt; this.startedAt = null;
        this.errorCode = truncate(code, 64); this.errorMessage = truncate(message, 1000);
    }

    public void fail(String code, String message) {
        this.status = InterviewRecapJobStatus.FAILED; this.finishedAt = Instant.now();
        this.errorCode = truncate(code, 64); this.errorMessage = truncate(message, 1000);
    }

    /**
     * Puts a failed job back in the queue at the user's request.
     *
     * <p>Unlike the automatic {@link #retry}, this resets the attempt counter: the user
     * has presumably fixed whatever caused the failure (a bad Base URL, a missing key),
     * so the previous attempts should not count against the new run.
     */
    public void requeue() {
        if (status != InterviewRecapJobStatus.FAILED) {
            throw new IllegalStateException("只有失败的整理任务可以重试");
        }
        this.status = InterviewRecapJobStatus.QUEUED;
        this.availableAt = Instant.now();
        this.attempts = 0;
        this.startedAt = null;
        this.finishedAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRecapId() { return recapId; }
    public String getInputHash() { return inputHash; }
    public String getRawContent() { return rawContent; }
    public InterviewRecapJobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

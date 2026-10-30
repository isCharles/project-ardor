package com.projectardor.assistant.automation.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "automations")
public class Automation {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, columnDefinition = "text") private String prompt;
    @Column(name = "cron_expression", nullable = false, length = 120) private String cronExpression;
    @Column(nullable = false, length = 64) private String timezone;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "delete_after_run", nullable = false) private boolean deleteAfterRun;
    @Column(name = "next_run_at") private Instant nextRunAt;
    @Column(name = "last_run_at") private Instant lastRunAt;
    @Column(name = "last_status", length = 32) private String lastStatus;
    @Column(name = "last_error", length = 1000) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Automation() {}

    public static Automation create(UUID userId, String title, String prompt, String cronExpression,
            String timezone, boolean deleteAfterRun, Instant nextRunAt) {
        Automation automation = new Automation();
        automation.id = UUID.randomUUID();
        automation.userId = userId;
        automation.title = title;
        automation.prompt = prompt;
        automation.cronExpression = cronExpression;
        automation.timezone = timezone;
        automation.enabled = true;
        automation.deleteAfterRun = deleteAfterRun;
        automation.nextRunAt = nextRunAt;
        return automation;
    }

    public void update(String title, String prompt, String cronExpression, String timezone, Boolean enabled,
            Boolean deleteAfterRun, Instant nextRunAt) {
        if (title != null) this.title = title;
        if (prompt != null) this.prompt = prompt;
        if (cronExpression != null) this.cronExpression = cronExpression;
        if (timezone != null) this.timezone = timezone;
        if (enabled != null) this.enabled = enabled;
        if (deleteAfterRun != null) this.deleteAfterRun = deleteAfterRun;
        this.nextRunAt = nextRunAt;
        this.updatedAt = Instant.now();
    }

    public void markStarted(Instant startedAt, Instant nextRunAt) {
        this.lastRunAt = startedAt;
        this.lastStatus = "RUNNING";
        this.lastError = null;
        this.nextRunAt = nextRunAt;
        this.updatedAt = Instant.now();
    }

    public void markFinished(String status, String error, Instant nextRunAt) {
        this.lastStatus = status;
        this.lastError = error == null || error.isBlank() ? null : error.substring(0, Math.min(1000, error.length()));
        this.nextRunAt = nextRunAt;
        this.updatedAt = Instant.now();
    }

    /** A failed one-time run must not retry automatically; keep it visible for inspection. */
    public void markFailedAndPause(String error) {
        this.enabled = false;
        this.lastStatus = "FAILED";
        this.lastError = error == null || error.isBlank() ? null : error.substring(0, Math.min(1000, error.length()));
        this.nextRunAt = null;
        this.updatedAt = Instant.now();
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getPrompt() { return prompt; }
    public String getCronExpression() { return cronExpression; }
    public String getTimezone() { return timezone; }
    public boolean isEnabled() { return enabled; }
    public boolean isDeleteAfterRun() { return deleteAfterRun; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public String getLastStatus() { return lastStatus; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

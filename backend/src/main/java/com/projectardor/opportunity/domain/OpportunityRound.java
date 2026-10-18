package com.projectardor.opportunity.domain;

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

/** One scheduled or completed round of a {@link JobOpportunity}. */
@Entity
@Table(name = "job_opportunity_rounds")
public class OpportunityRound {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "opportunity_id", nullable = false) private UUID opportunityId;
    @Column(name = "round_number", nullable = false) private int roundNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private RoundStage stage;
    @Column(name = "scheduled_at") private Instant scheduledAt;
    @Column(name = "occurred_at") private Instant occurredAt;
    @Column(name = "duration_minutes") private Integer durationMinutes;
    @Column(columnDefinition = "text") private String note;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected OpportunityRound() {}

    private OpportunityRound(UUID userId, UUID opportunityId, int roundNumber, RoundStage stage,
            Instant scheduledAt, Instant occurredAt, Integer durationMinutes, String note) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.opportunityId = opportunityId;
        this.roundNumber = roundNumber;
        this.stage = stage;
        this.scheduledAt = scheduledAt;
        this.occurredAt = occurredAt;
        this.durationMinutes = durationMinutes;
        this.note = note;
    }

    public static OpportunityRound create(UUID userId, UUID opportunityId, int roundNumber, RoundStage stage,
            Instant scheduledAt, Instant occurredAt, String note) {
        return create(userId, opportunityId, roundNumber, stage, scheduledAt, occurredAt, null, note);
    }

    public static OpportunityRound create(UUID userId, UUID opportunityId, int roundNumber, RoundStage stage,
            Instant scheduledAt, Instant occurredAt, Integer durationMinutes, String note) {
        if (roundNumber < 1) throw new IllegalArgumentException("轮次必须从 1 开始");
        if (stage == null) throw new IllegalArgumentException("面试环节不能为空");
        if (durationMinutes != null && (durationMinutes < 1 || durationMinutes > 480)) {
            throw new IllegalArgumentException("面试时长须在 1 到 480 分钟之间");
        }
        return new OpportunityRound(userId, opportunityId, roundNumber, stage, scheduledAt, occurredAt,
                durationMinutes, note);
    }

    public void update(RoundStage stage, Instant scheduledAt, Instant occurredAt, String note) {
        update(stage, scheduledAt, occurredAt, durationMinutes, note);
    }

    public void update(RoundStage stage, Instant scheduledAt, Instant occurredAt, Integer durationMinutes,
            String note) {
        if (durationMinutes != null && (durationMinutes < 1 || durationMinutes > 480)) {
            throw new IllegalArgumentException("面试时长须在 1 到 480 分钟之间");
        }
        if (stage != null) this.stage = stage;
        this.scheduledAt = scheduledAt;
        this.occurredAt = occurredAt;
        this.durationMinutes = durationMinutes;
        this.note = note;
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getOpportunityId() { return opportunityId; }
    public int getRoundNumber() { return roundNumber; }
    public RoundStage getStage() { return stage; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getOccurredAt() { return occurredAt; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}

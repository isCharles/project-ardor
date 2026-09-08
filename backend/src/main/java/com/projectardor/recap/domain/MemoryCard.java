package com.projectardor.recap.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;

@Entity
@Table(name = "memory_cards")
public class MemoryCard {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "recap_question_id") private UUID recapQuestionId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", nullable = false, length = 32) private MemoryCardSource sourceType;
    @Column(name = "source_label", length = 240) private String sourceLabel;
    @Column(name = "source_url", length = 1000) private String sourceUrl;
    @Column(nullable = false, columnDefinition = "text") private String front;
    @Column(nullable = false, columnDefinition = "text") private String back;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> tags = new ArrayList<>();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private MemoryCardStatus status;
    @Column(name = "next_review_at", nullable = false) private Instant nextReviewAt;
    @Column(name = "interval_days", nullable = false) private int intervalDays;
    @Column(name = "ease_factor", nullable = false, precision = 4, scale = 2) private BigDecimal easeFactor;
    @Column(nullable = false) private int repetitions;
    @Column(nullable = false) private int lapses;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected MemoryCard() {}
    private MemoryCard(UUID userId, UUID recapQuestionId, MemoryCardSource sourceType, String sourceLabel,
            String sourceUrl, String front, String back, List<String> tags, Instant nextReviewAt) {
        this.id = UUID.randomUUID(); this.userId = userId; this.recapQuestionId = recapQuestionId;
        this.sourceType = sourceType; this.sourceLabel = sourceLabel; this.sourceUrl = sourceUrl;
        this.front = front; this.back = back; this.tags = new ArrayList<>(tags); this.status = MemoryCardStatus.NEW;
        this.nextReviewAt = nextReviewAt; this.intervalDays = 0; this.easeFactor = new BigDecimal("2.50");
    }
    public static MemoryCard create(UUID userId, UUID recapQuestionId, MemoryCardSource sourceType, String sourceLabel,
            String sourceUrl, String front, String back, List<String> tags, Instant nextReviewAt) {
        return new MemoryCard(userId, recapQuestionId, sourceType, sourceLabel, sourceUrl, front, back, tags, nextReviewAt);
    }
    public ReviewResult review(MemoryCardRating rating, Instant now) {
        int previous = intervalDays;
        switch (rating) {
            case AGAIN -> { intervalDays = 1; repetitions = 0; lapses++; easeFactor = easeFactor.subtract(new BigDecimal("0.20")).max(new BigDecimal("1.30")); status = MemoryCardStatus.LEARNING; }
            case HARD -> { intervalDays = Math.max(1, (int) Math.ceil(Math.max(1, intervalDays) * 1.2)); easeFactor = easeFactor.subtract(new BigDecimal("0.15")).max(new BigDecimal("1.30")); repetitions++; status = MemoryCardStatus.LEARNING; }
            case GOOD -> { intervalDays = repetitions == 0 ? 1 : repetitions == 1 ? 3 : Math.max(4, (int) Math.round(intervalDays * easeFactor.doubleValue())); repetitions++; status = MemoryCardStatus.REVIEW; }
            case EASY -> { intervalDays = repetitions == 0 ? 4 : Math.max(5, (int) Math.round(Math.max(1, intervalDays) * (easeFactor.doubleValue() + 0.3))); repetitions++; easeFactor = easeFactor.add(new BigDecimal("0.15")); status = MemoryCardStatus.REVIEW; }
        }
        easeFactor = easeFactor.setScale(2, RoundingMode.HALF_UP);
        nextReviewAt = now.plus(intervalDays, ChronoUnit.DAYS);
        return new ReviewResult(previous, intervalDays);
    }
    public void suspend(boolean value) { status = value ? MemoryCardStatus.SUSPENDED : MemoryCardStatus.REVIEW; if (!value && nextReviewAt.isAfter(Instant.now())) nextReviewAt = Instant.now(); }
    public void edit(String front, String back, List<String> tags) {
        this.front = front;
        this.back = back;
        this.tags = new ArrayList<>(tags);
    }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public record ReviewResult(int previousIntervalDays, int nextIntervalDays) {}
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRecapQuestionId() { return recapQuestionId; }
    public MemoryCardSource getSourceType() { return sourceType; }
    public String getSourceLabel() { return sourceLabel; }
    public String getSourceUrl() { return sourceUrl; }
    public String getFront() { return front; }
    public String getBack() { return back; }
    public List<String> getTags() { return List.copyOf(tags); }
    public MemoryCardStatus getStatus() { return status; }
    public Instant getNextReviewAt() { return nextReviewAt; }
    public int getIntervalDays() { return intervalDays; }
    public BigDecimal getEaseFactor() { return easeFactor; }
    public int getRepetitions() { return repetitions; }
    public int getLapses() { return lapses; }
    public Instant getCreatedAt() { return createdAt; }
}

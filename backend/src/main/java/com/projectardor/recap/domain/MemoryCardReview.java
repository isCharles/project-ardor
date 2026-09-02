package com.projectardor.recap.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "memory_card_reviews")
public class MemoryCardReview {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "memory_card_id", nullable = false) private UUID memoryCardId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private MemoryCardRating rating;
    @Column(name = "previous_interval_days", nullable = false) private int previousIntervalDays;
    @Column(name = "next_interval_days", nullable = false) private int nextIntervalDays;
    @Column(name = "reviewed_at", nullable = false) private Instant reviewedAt;
    protected MemoryCardReview() {}
    private MemoryCardReview(UUID userId, UUID cardId, MemoryCardRating rating, int previous, int next) {
        this.id = UUID.randomUUID(); this.userId = userId; this.memoryCardId = cardId; this.rating = rating;
        this.previousIntervalDays = previous; this.nextIntervalDays = next;
    }
    public static MemoryCardReview create(UUID userId, UUID cardId, MemoryCardRating rating, int previous, int next) { return new MemoryCardReview(userId, cardId, rating, previous, next); }
    @PrePersist void onCreate() { reviewedAt = Instant.now(); }
}

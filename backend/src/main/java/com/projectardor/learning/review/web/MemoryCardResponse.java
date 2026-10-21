package com.projectardor.learning.review.web;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.projectardor.learning.review.domain.*;
public record MemoryCardResponse(UUID id, UUID recapQuestionId, MemoryCardSource sourceType, String sourceLabel,
        String sourceUrl, String front, String back, List<String> tags, MemoryCardStatus status,
        Instant nextReviewAt, int intervalDays, BigDecimal easeFactor, int repetitions, int lapses, Instant createdAt) {
    public static MemoryCardResponse from(com.projectardor.learning.review.service.FlashcardView card) {
        return new MemoryCardResponse(card.id(),card.recapQuestionId(),card.sourceType(),card.sourceLabel(),card.sourceUrl(),
                card.front(),card.back(),card.tags(),card.status(),card.nextReviewAt(),card.intervalDays(),card.easeFactor(),
                card.repetitions(),card.lapses(),card.createdAt());
    }
    public static MemoryCardResponse from(MemoryCard card) {
        return from(com.projectardor.learning.review.service.FlashcardView.from(card));
    }
}

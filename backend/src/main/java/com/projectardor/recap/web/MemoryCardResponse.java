package com.projectardor.recap.web;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.projectardor.recap.domain.*;
public record MemoryCardResponse(UUID id, UUID recapQuestionId, MemoryCardSource sourceType, String sourceLabel,
        String sourceUrl, String front, String back, List<String> tags, MemoryCardStatus status,
        Instant nextReviewAt, int intervalDays, BigDecimal easeFactor, int repetitions, int lapses, Instant createdAt) {
    public static MemoryCardResponse from(MemoryCard card) { return new MemoryCardResponse(card.getId(), card.getRecapQuestionId(),
            card.getSourceType(), card.getSourceLabel(), card.getSourceUrl(), card.getFront(), card.getBack(), card.getTags(),
            card.getStatus(), card.getNextReviewAt(), card.getIntervalDays(), card.getEaseFactor(), card.getRepetitions(),
            card.getLapses(), card.getCreatedAt()); }
}

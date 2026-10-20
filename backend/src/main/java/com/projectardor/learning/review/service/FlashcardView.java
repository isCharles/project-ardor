package com.projectardor.learning.review.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.projectardor.learning.review.domain.*;

/** Immutable application snapshot, preserving the existing HTTP and tool fields. */
public record FlashcardView(UUID id,UUID recapQuestionId,MemoryCardSource sourceType,String sourceLabel,
        String sourceUrl,String front,String back,List<String> tags,MemoryCardStatus status,
        Instant nextReviewAt,int intervalDays,BigDecimal easeFactor,int repetitions,int lapses,Instant createdAt) {
    public FlashcardView {tags=tags==null ? null : Collections.unmodifiableList(new ArrayList<>(tags));}
    public static FlashcardView from(MemoryCard card) {
        return new FlashcardView(card.getId(),card.getRecapQuestionId(),card.getSourceType(),card.getSourceLabel(),
                card.getSourceUrl(),card.getFront(),card.getBack(),card.getTags(),card.getStatus(),card.getNextReviewAt(),
                card.getIntervalDays(),card.getEaseFactor(),card.getRepetitions(),card.getLapses(),card.getCreatedAt());
    }
}

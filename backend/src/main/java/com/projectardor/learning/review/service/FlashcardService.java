package com.projectardor.learning.review.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.learning.review.domain.*;
import com.projectardor.learning.review.repository.MemoryCardRepository;
import com.projectardor.learning.review.repository.MemoryCardReviewRepository;
import com.projectardor.jobsearch.record.service.InterviewRecordSourceAccess;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.common.web.ResourceNotFoundException;

/** Learning owns card state and review history; interview records only supply owned source references. */
@Service
public class FlashcardService {
    private final MemoryCardRepository cardRepository;
    private final MemoryCardReviewRepository reviewRepository;
    private final InterviewRecordSourceAccess sourceAccess;
    private final CalendarTaskService calendarTaskService;
    private final ProfileService profileService;
    public FlashcardService(MemoryCardRepository cards,MemoryCardReviewRepository reviews,
            InterviewRecordSourceAccess sourceAccess,CalendarTaskService calendar,ProfileService profiles) {
        this.cardRepository=cards;this.reviewRepository=reviews;this.sourceAccess=sourceAccess;
        this.calendarTaskService=calendar;this.profileService=profiles;
    }
    @Transactional
    public FlashcardView createCard(UUID userId, MemoryCardSource sourceType, String sourceLabel, String sourceUrl,
            String front, String back, List<String> tags, Instant nextReviewAt, UUID recapQuestionId) {
        MemoryCardSource resolvedSource = sourceType == null ? MemoryCardSource.KNOWLEDGE : sourceType;
        String url = nullable(sourceUrl, 1000);
        if (resolvedSource == MemoryCardSource.WEB && url == null) {
            throw new IllegalArgumentException("网络题必须提供可核验的来源链接");
        }
        if (recapQuestionId != null) {
            if (resolvedSource != MemoryCardSource.INTERVIEW) {
                throw new IllegalArgumentException("只有面试来源的记忆卡才能关联面经问题");
            }
            sourceAccess.requireQuestion(userId, recapQuestionId);
            if (cardRepository.findByUserIdAndRecapQuestionId(userId, recapQuestionId).isPresent()) {
                throw new IllegalArgumentException("这道面经问题已有记忆卡");
            }
        }
        MemoryCard card = cardRepository.save(MemoryCard.create(userId, recapQuestionId, resolvedSource,
                nullable(sourceLabel, 240), url, required(front, "卡片问题不能为空", 12000),
                required(back, "卡片答案不能为空", 20000), normalizeTags(tags),
                nextReviewAt == null ? Instant.now() : nextReviewAt));
        refreshDate(userId, localDate(userId, card.getNextReviewAt()));
        return FlashcardView.from(card);
    }

    @Transactional(readOnly = true)
    public List<FlashcardView> listCards(UUID userId, boolean dueOnly) {
        if (dueOnly) return cardRepository.findAllByUserIdAndStatusNotAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(
                userId, MemoryCardStatus.SUSPENDED, Instant.now()).stream().map(FlashcardView::from).toList();
        return cardRepository.findAllByUserIdOrderByNextReviewAtAscCreatedAtDesc(userId).stream().map(FlashcardView::from).toList();
    }

    @Transactional
    public FlashcardView updateCard(UUID userId, UUID cardId, String front, String back, List<String> tags) {
        MemoryCard card = getCard(userId, cardId);
        card.edit(required(front, "卡片问题不能为空", 12000),
                required(back, "卡片答案不能为空", 20000), normalizeTags(tags));
        return FlashcardView.from(card);
    }

    @Transactional
    public FlashcardView review(UUID userId, UUID cardId, MemoryCardRating rating) {
        MemoryCard card = getCard(userId, cardId);
        if (card.getStatus() == MemoryCardStatus.SUSPENDED) throw new IllegalStateException("已暂停的卡片不能复习");
        if (rating == null) throw new IllegalArgumentException("复习结果不能为空");
        LocalDate previousDate = localDate(userId, card.getNextReviewAt());
        MemoryCard.ReviewResult result = card.review(rating, Instant.now());
        reviewRepository.save(MemoryCardReview.create(userId, cardId, rating,
                result.previousIntervalDays(), result.nextIntervalDays()));
        LocalDate nextDate = localDate(userId, card.getNextReviewAt());
        refreshDate(userId, previousDate);
        if (!nextDate.equals(previousDate)) refreshDate(userId, nextDate);
        return FlashcardView.from(card);
    }

    @Transactional
    public FlashcardView suspend(UUID userId, UUID cardId, boolean suspended) {
        MemoryCard card = getCard(userId, cardId);
        LocalDate previousDate = localDate(userId, card.getNextReviewAt());
        card.suspend(suspended);
        LocalDate nextDate = localDate(userId, card.getNextReviewAt());
        refreshDate(userId, previousDate);
        if (!nextDate.equals(previousDate)) refreshDate(userId, nextDate);
        return FlashcardView.from(card);
    }

    @Transactional
    public void deleteCard(UUID userId, UUID cardId) {
        MemoryCard card = getCard(userId, cardId);
        LocalDate date = localDate(userId, card.getNextReviewAt());
        cardRepository.delete(card);
        cardRepository.flush();
        refreshDate(userId, date);
    }

    @Transactional
    public void deleteAllCards(UUID userId) {
        List<MemoryCard> cards = cardRepository.findAllByUserIdOrderByNextReviewAtAscCreatedAtDesc(userId);
        Set<LocalDate> dates = cards.stream().map(card -> localDate(userId, card.getNextReviewAt()))
                .collect(java.util.stream.Collectors.toSet());
        cardRepository.deleteAll(cards);
        cardRepository.flush();
        dates.forEach(date -> refreshDate(userId, date));
    }

    @Transactional
    public void deleteCardSnapshot(UUID userId, List<UUID> ids) {
        var cards = ids.stream().distinct().map(id -> getCard(userId, id)).toList();
        Set<LocalDate> dates = cards.stream().map(card -> localDate(userId, card.getNextReviewAt()))
                .collect(java.util.stream.Collectors.toSet());
        cardRepository.deleteAll(cards);
        cardRepository.flush();
        dates.forEach(date -> refreshDate(userId, date));
    }

    private MemoryCard getCard(UUID userId, UUID cardId) {
        return cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆卡不存在"));
    }

    private void refreshDate(UUID userId, LocalDate date) {
        ZoneId userZone = userZone(userId);
        Instant from = date.atStartOfDay(userZone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(userZone).toInstant();
        long count = cardRepository.countByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThan(
                userId, MemoryCardStatus.SUSPENDED, from, to);
        Instant firstDue = cardRepository.findFirstByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThanOrderByNextReviewAtAsc(
                userId, MemoryCardStatus.SUSPENDED, from, to).map(MemoryCard::getNextReviewAt).orElse(from);
        calendarTaskService.refreshMemoryCardReview(userId, date, Math.toIntExact(count), firstDue);
    }

    private LocalDate localDate(UUID userId, Instant value) { return value.atZone(userZone(userId)).toLocalDate(); }

    private ZoneId userZone(UUID userId) { return ZoneId.of(profileService.get(userId).getTimezone()); }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                .map(value -> value.substring(0, Math.min(value.length(), 80))).distinct().limit(12).toList();
    }
    private String nullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip(); return normalized.substring(0, Math.min(normalized.length(), max));
    }
    private String required(String value, String message, int max) {
        String normalized = nullable(value, max); if (normalized == null) throw new IllegalArgumentException(message); return normalized;
    }
}

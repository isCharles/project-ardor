package com.projectardor.learning.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.recap.domain.InterviewRecapQuestion;
import com.projectardor.recap.domain.InterviewRecap;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.learning.review.domain.MemoryCard;
import com.projectardor.learning.review.domain.MemoryCardSource;
import com.projectardor.recap.domain.QuestionPerformance;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;
import com.projectardor.learning.review.repository.MemoryCardRepository;
import com.projectardor.learning.review.repository.MemoryCardReviewRepository;

class FlashcardSourceTests {
    private final InterviewRecapQuestionRepository questions = mock(InterviewRecapQuestionRepository.class);
    private final MemoryCardRepository cards = mock(MemoryCardRepository.class);
    private final ProfileService profiles = mock(ProfileService.class);
    private final InterviewRecapRepository records=mock(InterviewRecapRepository.class);
    private final FlashcardService service = new FlashcardService(cards,mock(MemoryCardReviewRepository.class),
            new InterviewRecordSourceAccess(records,questions),mock(CalendarTaskService.class),profiles);

    @Test
    void cardCanKeepTheOwnedQuestionAsEvidence() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID questionId = source.getId();
        when(questions.findByIdAndUserId(questionId, userId)).thenReturn(Optional.of(source));
        when(cards.findByUserIdAndRecapQuestionId(userId, questionId)).thenReturn(Optional.empty());
        when(cards.save(any())).thenAnswer(call -> call.getArgument(0));
        UserProfile profile = mock(UserProfile.class);
        when(profile.getTimezone()).thenReturn("Asia/Shanghai");
        when(profiles.get(userId)).thenReturn(profile);

        FlashcardView card = service.createCard(userId, MemoryCardSource.INTERVIEW, "面经", null,
                "解释 JVM 内存模型", "回答框架", List.of("JVM"), Instant.now(), questionId);

        assertThat(card.recapQuestionId()).isEqualTo(questionId);
    }

    @Test
    void anotherUsersQuestionCannotBeLinked() {
        UUID userId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        when(questions.findByIdAndUserId(questionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCard(userId, MemoryCardSource.INTERVIEW, null, null,
                "问题", "答案", List.of(), Instant.now(), questionId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(cards, never()).save(any());
    }

    @Test
    void nonInterviewCardCannotClaimInterviewEvidence() {
        UUID questionId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createCard(UUID.randomUUID(), MemoryCardSource.AGENT, null, null,
                "问题", "答案", List.of(), Instant.now(), questionId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cards, never()).save(any());
    }

    @Test
    void sameQuestionDoesNotCreateTwoCards() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID questionId = source.getId();
        when(questions.findByIdAndUserId(questionId, userId)).thenReturn(Optional.of(source));
        when(cards.findByUserIdAndRecapQuestionId(userId, questionId))
                .thenReturn(Optional.of(mock(MemoryCard.class)));

        assertThatThrownBy(() -> service.createCard(userId, MemoryCardSource.INTERVIEW, null, null,
                "问题", "答案", List.of(), Instant.now(), questionId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cards, never()).save(any());
    }

    private InterviewRecapQuestion question(UUID userId) {
        var question=InterviewRecapQuestion.create(userId, UUID.randomUUID(), 1, "解释 JVM", null,
                List.of(), "需补充", QuestionPerformance.WEAK, "概念不清", null, List.of("JVM"));
        var record=mock(InterviewRecap.class);
        when(record.getId()).thenReturn(question.getRecapId());
        when(records.findByIdAndUserId(question.getRecapId(),userId)).thenReturn(Optional.of(record));
        return question;
    }
}

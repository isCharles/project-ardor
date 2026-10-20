package com.projectardor.learning.review.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.review.domain.*;
import com.projectardor.learning.review.repository.*;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.recap.service.InterviewRecordSourceAccess;

class FlashcardLifecycleTests {
    private final UUID owner=UUID.randomUUID();
    private final MemoryCardRepository cards=mock(MemoryCardRepository.class);
    private final MemoryCardReviewRepository reviews=mock(MemoryCardReviewRepository.class);
    private final InterviewRecordSourceAccess sources=mock(InterviewRecordSourceAccess.class);
    private final CalendarTaskService calendar=mock(CalendarTaskService.class);
    private final ProfileService profiles=mock(ProfileService.class);
    private final FlashcardService service=new FlashcardService(cards,reviews,sources,calendar,profiles);
    private MemoryCard card() {
        var card=MemoryCard.create(owner,null,MemoryCardSource.AGENT,"Fixture",null,"Question","Answer",List.of("tag"),Instant.parse("2030-01-01T01:00:00Z"));
        when(cards.findByIdAndUserId(card.getId(),owner)).thenReturn(Optional.of(card));
        return card;
    }
    private void timezone() {
        var profile=mock(UserProfile.class);when(profile.getTimezone()).thenReturn("Asia/Shanghai");when(profiles.get(owner)).thenReturn(profile);
    }
    @Test void autonomousCardsNeedNoInterviewButKeepOwnedCalendarCoordination() {
        timezone();when(cards.save(any())).thenAnswer(call->call.getArgument(0));
        var result=service.createCard(owner,MemoryCardSource.AGENT,"Fixture",null," Q "," A ",List.of("tag"),Instant.now(),null);
        assertThat(result.recapQuestionId()).isNull();assertThat(result.front()).isEqualTo("Q");
        verifyNoInteractions(sources);verify(calendar).refreshMemoryCardReview(eq(owner),any(),eq(0),any());
    }
    @Test void textEditsPreserveReviewStateAndDoNotScheduleAnotherReview() {
        var card=card();card.review(MemoryCardRating.GOOD,Instant.parse("2026-10-01T01:00:00Z"));
        card.review(MemoryCardRating.GOOD,Instant.parse("2026-10-02T01:00:00Z"));
        var due=card.getNextReviewAt();var result=service.updateCard(owner,card.getId()," New question "," New answer ",List.of(" x ","x"));
        assertThat(result.front()).isEqualTo("New question");assertThat(result.tags()).containsExactly("x");
        assertThat(result.repetitions()).isEqualTo(2);assertThat(result.intervalDays()).isEqualTo(3);
        assertThat(result.status()).isEqualTo(MemoryCardStatus.REVIEW);assertThat(result.nextReviewAt()).isEqualTo(due);
        verifyNoInteractions(reviews,calendar,sources,profiles);
    }
    @Test void reviewWritesExactlyOneOwnedHistoryEntryAndRefreshesAffectedDates() {
        timezone();var card=card();var result=service.review(owner,card.getId(),MemoryCardRating.GOOD);
        assertThat(result.repetitions()).isEqualTo(1);assertThat(result.intervalDays()).isEqualTo(1);
        verify(reviews,times(1)).save(any(MemoryCardReview.class));
        verify(calendar,atLeastOnce()).refreshMemoryCardReview(eq(owner),any(),eq(0),any());
    }
    @Test void suspendedCardsCannotCreateHistoryOrCalendarSideEffects() {
        var card=card();card.suspend(true);
        assertThatThrownBy(()->service.review(owner,card.getId(),MemoryCardRating.GOOD)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reviews,calendar,profiles,sources);
    }
    @Test void foreignIdInSnapshotPreventsAnyDeletionOrRefresh() {
        var card=card();UUID foreign=UUID.randomUUID();
        when(cards.findByIdAndUserId(foreign,owner)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.deleteCardSnapshot(owner,List.of(card.getId(),foreign))).isInstanceOf(ResourceNotFoundException.class);
        verify(cards,never()).deleteAll(anyIterable());verify(cards,never()).flush();verifyNoInteractions(calendar,reviews,sources);
    }
    @Test void snapshotDeletesOnlyDistinctListedOwnedCardsAndFlushesBeforeRecount() {
        timezone();var card=card();service.deleteCardSnapshot(owner,List.of(card.getId(),card.getId()));
        var order=inOrder(cards,calendar);
        order.verify(cards).findByIdAndUserId(card.getId(),owner);
        order.verify(cards).deleteAll(List.of(card));order.verify(cards).flush();
        order.verify(cards).countByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThan(eq(owner),eq(MemoryCardStatus.SUSPENDED),any(),any());
        order.verify(cards).findFirstByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThanOrderByNextReviewAtAsc(eq(owner),eq(MemoryCardStatus.SUSPENDED),any(),any());
        order.verify(calendar).refreshMemoryCardReview(eq(owner),any(),eq(0),any());
    }
}

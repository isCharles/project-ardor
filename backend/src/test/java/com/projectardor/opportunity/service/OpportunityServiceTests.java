package com.projectardor.opportunity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.domain.OpportunityRound;
import com.projectardor.opportunity.domain.OpportunityStatus;
import com.projectardor.opportunity.domain.RoundStage;
import com.projectardor.opportunity.repository.JobOpportunityRepository;
import com.projectardor.opportunity.repository.OpportunityRoundRepository;
import com.projectardor.opportunity.web.OpportunityCreateRequest;
import com.projectardor.opportunity.web.OpportunityResponse;
import com.projectardor.opportunity.web.OpportunityRoundRequest;
import com.projectardor.opportunity.web.OpportunityUpdateRequest;

class OpportunityServiceTests {

    private final JobOpportunityRepository opportunities = mock(JobOpportunityRepository.class);
    private final OpportunityRoundRepository rounds = mock(OpportunityRoundRepository.class);
    private final OpportunityService service = new OpportunityService(opportunities, rounds);

    private void saveReturnsArguments() {
        when(opportunities.save(any())).thenAnswer(call -> call.getArgument(0));
        when(rounds.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void listOnlyAsksForTheCurrentUsersProcesses() {
        UUID userId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "字节跳动", "后端实习生", null, null);
        when(opportunities.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(owned));
        when(rounds.findAllByUserIdAndOpportunityIdInOrderByRoundNumberAsc(eq(userId), any()))
                .thenReturn(List.of());

        List<OpportunityResponse> result = service.list(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().company()).isEqualTo("字节跳动");
        assertThat(result.getFirst().currentRound()).isZero();
    }

    @Test
    void detailRejectsAProcessOwnedByAnotherUser() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(userId, processId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("面试流程不存在");
    }

    @Test
    void createWithAScheduledTimeRecordsRoundOneAndSchedulesTheProcess() {
        UUID userId = UUID.randomUUID();
        saveReturnsArguments();
        when(rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(any(), any())).thenReturn(List.of());

        service.create(userId, new OpportunityCreateRequest(
                "字节跳动", "后端开发实习生", "飞书", "JD", "邀请原文",
                "TECHNICAL", Instant.parse("2026-10-20T02:00:00Z"), 50, "一面"));

        ArgumentCaptor<JobOpportunity> created = ArgumentCaptor.forClass(JobOpportunity.class);
        verify(opportunities, org.mockito.Mockito.atLeastOnce()).save(created.capture());
        assertThat(created.getValue().getStatus()).isEqualTo(OpportunityStatus.SCHEDULED);

        ArgumentCaptor<OpportunityRound> round = ArgumentCaptor.forClass(OpportunityRound.class);
        verify(rounds).save(round.capture());
        assertThat(round.getValue().getRoundNumber()).isEqualTo(1);
        assertThat(round.getValue().getStage()).isEqualTo(RoundStage.TECHNICAL);
        assertThat(round.getValue().getDurationMinutes()).isEqualTo(50);
        assertThat(round.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void createWithoutDetailsStaysInvitedAndCreatesNoRound() {
        UUID userId = UUID.randomUUID();
        saveReturnsArguments();
        when(rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(any(), any())).thenReturn(List.of());

        service.create(userId, new OpportunityCreateRequest(
                "Shopee", "", "", "", "", "", null, null, ""));

        ArgumentCaptor<JobOpportunity> created = ArgumentCaptor.forClass(JobOpportunity.class);
        verify(opportunities).save(created.capture());
        assertThat(created.getValue().getStatus()).isEqualTo(OpportunityStatus.INVITED);
        assertThat(created.getValue().getTargetRole()).isNull();
        verify(rounds, never()).save(any());
    }

    @Test
    void updateRejectsSkippingStraightToAnOffer() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "Rednote", "Java 实习生", null, null);
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.of(owned));

        assertThatThrownBy(() -> service.update(userId, processId,
                new OpportunityUpdateRequest(null, null, null, null, "OFFER")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owned.getStatus()).isEqualTo(OpportunityStatus.INVITED);
    }

    @Test
    void updateAppliesAnAllowedTransition() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "Rednote", "Java 实习生", null, null);
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.of(owned));
        when(rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(any(), any())).thenReturn(List.of());

        OpportunityResponse result = service.update(userId, processId,
                new OpportunityUpdateRequest(null, null, null, null, "SCHEDULED"));

        assertThat(result.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void addRoundRejectsADuplicateRoundNumber() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "快手", "AIGC 实习生", null, null);
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.of(owned));
        OpportunityRound existing = OpportunityRound.create(userId, processId, 1, RoundStage.HR, null, null, null);
        when(rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(userId, processId))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.addRound(userId, processId,
                new OpportunityRoundRequest(1, "TECHNICAL", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
        verify(rounds, never()).save(any());
    }

    @Test
    void addRoundDefaultsToTheNextFreeNumber() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "快手", "AIGC 实习生", null, null);
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.of(owned));
        OpportunityRound first = OpportunityRound.create(userId, processId, 1, RoundStage.HR, null, null, null);
        when(rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(any(), any()))
                .thenReturn(List.of(first));
        saveReturnsArguments();

        service.addRound(userId, processId,
                new OpportunityRoundRequest(null, "TECHNICAL", null, null, 60, null));

        ArgumentCaptor<OpportunityRound> saved = ArgumentCaptor.forClass(OpportunityRound.class);
        verify(rounds).save(saved.capture());
        assertThat(saved.getValue().getRoundNumber()).isEqualTo(2);
        assertThat(saved.getValue().getStage()).isEqualTo(RoundStage.TECHNICAL);
    }

    @Test
    void deleteRoundRejectsARoundThatIsNotOwnedByTheCaller() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        when(opportunities.findByIdAndUserId(processId, userId))
                .thenReturn(Optional.of(JobOpportunity.create(userId, "Shopee", "后端", null, null)));
        when(rounds.findByIdAndUserId(roundId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRound(userId, processId, roundId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("面试轮次不存在");
        verify(rounds, never()).delete(any());
    }

    @Test
    void deleteOnlyRemovesTheOwnedProcess() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        JobOpportunity owned = JobOpportunity.create(userId, "Shopee", "后端", null, null);
        when(opportunities.findByIdAndUserId(processId, userId)).thenReturn(Optional.of(owned));

        service.delete(userId, processId);

        verify(opportunities).delete(owned);
    }

    @Test
    void updateRoundRejectsRenumbering() {
        UUID userId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        when(opportunities.findByIdAndUserId(processId, userId))
                .thenReturn(Optional.of(JobOpportunity.create(userId, "Shopee", "后端", null, null)));
        OpportunityRound round = OpportunityRound.create(userId, processId, 1, RoundStage.HR, null, null, null);
        when(rounds.findByIdAndUserId(roundId, userId)).thenReturn(Optional.of(round));

        assertThatThrownBy(() -> service.updateRound(userId, processId, roundId,
                new OpportunityRoundRequest(2, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("轮次编号不能");
    }
}

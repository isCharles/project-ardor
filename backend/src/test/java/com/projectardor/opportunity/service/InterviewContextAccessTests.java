package com.projectardor.opportunity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.domain.OpportunityRound;
import com.projectardor.opportunity.domain.RoundStage;
import com.projectardor.opportunity.repository.JobOpportunityRepository;
import com.projectardor.opportunity.repository.OpportunityRoundRepository;

class InterviewContextAccessTests {
    private final JobOpportunityRepository processes = mock(JobOpportunityRepository.class);
    private final OpportunityRoundRepository rounds = mock(OpportunityRoundRepository.class);
    private final InterviewContextAccess access = new InterviewContextAccess(processes, rounds);
    private final UUID owner = UUID.randomUUID(), process = UUID.randomUUID();

    private void ownedProcess() {
        when(processes.findByIdAndUserId(process, owner)).thenReturn(Optional.of(JobOpportunity.create(owner, "Fixture", null, null, null)));
    }

    @Test void legacyProcessOnlyContextDoesNotInventARound() {
        ownedProcess();
        assertThat(access.require(owner, process, null)).isEqualTo(new InterviewContextAccess.Context(process, null));
        verifyNoInteractions(rounds);
    }

    @Test void roundMustBelongToBothAuthenticatedOwnerAndSelectedProcess() {
        ownedProcess();
        var round = OpportunityRound.create(owner, process, 2, RoundStage.TECHNICAL, null, null, null);
        when(rounds.findByIdAndUserId(round.getId(), owner)).thenReturn(Optional.of(round));
        assertThat(access.require(owner, process, round.getId()).roundId()).isEqualTo(round.getId());
        assertThatThrownBy(() -> access.require(UUID.randomUUID(), process, round.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void ownedRoundFromAnotherProcessIsNotAnAuthorizedParentChildPair() {
        ownedProcess();
        var otherRound = OpportunityRound.create(owner, UUID.randomUUID(), 1, RoundStage.HR, null, null, null);
        when(rounds.findByIdAndUserId(otherRound.getId(), owner)).thenReturn(Optional.of(otherRound));
        assertThatThrownBy(() -> access.require(owner, process, otherRound.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void missingOrForeignRoundIsNotExposed() {
        ownedProcess();
        assertThatThrownBy(() -> access.require(owner, process, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void absentProcessDoesNotProbeRoundOwnership() {
        assertThatThrownBy(() -> access.require(owner, process, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(rounds);
        assertThatThrownBy(() -> access.require(owner, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

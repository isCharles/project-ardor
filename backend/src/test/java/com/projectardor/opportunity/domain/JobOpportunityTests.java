package com.projectardor.opportunity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobOpportunityTests {

    private static JobOpportunity booked() {
        return JobOpportunity.create(UUID.randomUUID(), "字节跳动", "后端工程师", null, null);
    }

    @Test
    void startsScheduledAndNotTerminal() {
        JobOpportunity opportunity = booked();

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.SCHEDULED);
        assertThat(opportunity.isTerminal()).isFalse();
    }

    @Test
    void movingToTheNextRound() {
        JobOpportunity opportunity = booked();

        opportunity.transitionTo(OpportunityStatus.NEXT_ROUND);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.NEXT_ROUND);
    }

    @Test
    void awaitsResultAfterTheInterviewHappened() {
        JobOpportunity opportunity = booked();

        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.AWAITING_RESULT);
    }

    @Test
    void refusesAwaitingResultFromNextRoundBecauseThatRoundHasNotHappened() {
        JobOpportunity opportunity = booked();
        opportunity.transitionTo(OpportunityStatus.NEXT_ROUND);

        assertThatThrownBy(() -> opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.NEXT_ROUND);
    }

    @Test
    void terminatesAsRejected() {
        JobOpportunity opportunity = booked();
        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        opportunity.transitionTo(OpportunityStatus.REJECTED);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.REJECTED);
        assertThat(opportunity.isTerminal()).isTrue();
    }

    @Test
    void allowsCorrectingARecordedOutcome() {
        JobOpportunity opportunity = booked();
        opportunity.transitionTo(OpportunityStatus.REJECTED);

        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.AWAITING_RESULT);
    }

    @Test
    void refusesMovingFromRejectedStraightToTheNextRound() {
        JobOpportunity opportunity = booked();
        opportunity.transitionTo(OpportunityStatus.REJECTED);

        assertThatThrownBy(() -> opportunity.transitionTo(OpportunityStatus.NEXT_ROUND))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsSameStatusAsNoop() {
        JobOpportunity opportunity = booked();

        opportunity.transitionTo(OpportunityStatus.SCHEDULED);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.SCHEDULED);
    }

    @Test
    void rejectsBlankCompany() {
        assertThatThrownBy(() -> JobOpportunity.create(UUID.randomUUID(), "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownsStatusChangesAndKeepsOwner() {
        UUID userId = UUID.randomUUID();
        JobOpportunity opportunity = JobOpportunity.create(userId, " 示例公司 ", "岗位", "JD", "邀请");

        assertThat(opportunity.getUserId()).isEqualTo(userId);
        assertThat(opportunity.getCompany()).isEqualTo("示例公司");
        assertThat(JobOpportunity.canMove(OpportunityStatus.AWAITING_RESULT, OpportunityStatus.NEXT_ROUND)).isTrue();
        assertThat(JobOpportunity.canMove(OpportunityStatus.NEXT_ROUND, OpportunityStatus.AWAITING_RESULT)).isFalse();
    }
}

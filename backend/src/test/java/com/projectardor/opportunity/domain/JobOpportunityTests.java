package com.projectardor.opportunity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobOpportunityTests {

    private static JobOpportunity invite() {
        return JobOpportunity.create(UUID.randomUUID(), "字节跳动", "后端工程师", null, null);
    }

    @Test
    void startsInvitedAndNotTerminal() {
        JobOpportunity opportunity = invite();

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.INVITED);
        assertThat(opportunity.isTerminal()).isFalse();
    }

    @Test
    void schedulesFromInvited() {
        JobOpportunity opportunity = invite();

        opportunity.transitionTo(OpportunityStatus.SCHEDULED);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.SCHEDULED);
    }

    @Test
    void refusesToSkipFromInvitedToOffer() {
        JobOpportunity opportunity = invite();

        assertThatThrownBy(() -> opportunity.transitionTo(OpportunityStatus.OFFER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.INVITED);
    }

    @Test
    void awaitsResultOnlyAfterTheInterviewHappened() {
        JobOpportunity opportunity = invite();
        opportunity.transitionTo(OpportunityStatus.SCHEDULED);

        assertThatThrownBy(() -> opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT))
                .isInstanceOf(IllegalStateException.class);

        opportunity.transitionTo(OpportunityStatus.INTERVIEWED);
        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.AWAITING_RESULT);
    }

    @Test
    void terminatesFromAwaitingResult() {
        JobOpportunity opportunity = invite();
        opportunity.transitionTo(OpportunityStatus.INTERVIEWED);
        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        opportunity.transitionTo(OpportunityStatus.REJECTED);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.REJECTED);
        assertThat(opportunity.isTerminal()).isTrue();
    }

    @Test
    void allowsCorrectingARecordedOutcome() {
        JobOpportunity opportunity = invite();
        opportunity.transitionTo(OpportunityStatus.REJECTED);

        opportunity.transitionTo(OpportunityStatus.AWAITING_RESULT);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.AWAITING_RESULT);
    }

    @Test
    void refusesMovingFromTerminalToAnotherTerminal() {
        JobOpportunity opportunity = invite();
        opportunity.transitionTo(OpportunityStatus.WITHDRAWN);

        assertThatThrownBy(() -> opportunity.transitionTo(OpportunityStatus.OFFER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsSameStatusAsNoop() {
        JobOpportunity opportunity = invite();

        opportunity.transitionTo(OpportunityStatus.INVITED);

        assertThat(opportunity.getStatus()).isEqualTo(OpportunityStatus.INVITED);
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
        assertThat(JobOpportunity.canMove(OpportunityStatus.AWAITING_RESULT, OpportunityStatus.OFFER)).isTrue();
        assertThat(JobOpportunity.canMove(OpportunityStatus.INVITED, OpportunityStatus.AWAITING_RESULT)).isFalse();
    }
}

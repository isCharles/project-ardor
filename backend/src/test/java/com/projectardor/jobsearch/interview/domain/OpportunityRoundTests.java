package com.projectardor.jobsearch.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpportunityRoundTests {
    @Test void updatingDurationUsesTheSameBoundsBeforeMutatingFields() {
        var round = OpportunityRound.create(USER, OPPORTUNITY, 1, RoundStage.HR, null, null, 30, "Original");
        for (int invalid : new int[]{-1, 0, 481}) {
            assertThatThrownBy(() -> round.update(RoundStage.MANAGER, null, null, invalid, "Changed"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(round.getStage()).isEqualTo(RoundStage.HR);
            assertThat(round.getDurationMinutes()).isEqualTo(30);
        }
        round.update(null, null, null, 480, "Valid");
        assertThat(round.getDurationMinutes()).isEqualTo(480);
    }

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OPPORTUNITY = UUID.randomUUID();

    @Test
    void requiresAPositiveRoundNumber() {
        assertThatThrownBy(() -> OpportunityRound.create(USER, OPPORTUNITY, 0,
                RoundStage.TECHNICAL, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAStage() {
        assertThatThrownBy(() -> OpportunityRound.create(USER, OPPORTUNITY, 1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsOwnerAndOpportunity() {
        OpportunityRound round = OpportunityRound.create(USER, OPPORTUNITY, 2,
                RoundStage.TECHNICAL, null, null, "二面");

        assertThat(round.getUserId()).isEqualTo(USER);
        assertThat(round.getOpportunityId()).isEqualTo(OPPORTUNITY);
        assertThat(round.getRoundNumber()).isEqualTo(2);
        assertThat(round.getStage()).isEqualTo(RoundStage.TECHNICAL);
    }

    @Test
    void updateReplacesSchedulingFieldsAndKeepsStageWhenOmitted() {
        Instant scheduled = Instant.parse("2026-10-20T02:00:00Z");
        Instant occurred = Instant.parse("2026-10-20T03:10:00Z");
        OpportunityRound round = OpportunityRound.create(USER, OPPORTUNITY, 1,
                RoundStage.HR, scheduled, null, null);

        round.update(null, scheduled, occurred, "改期后的记录");

        assertThat(round.getStage()).isEqualTo(RoundStage.HR);
        assertThat(round.getOccurredAt()).isEqualTo(occurred);
        assertThat(round.getNote()).isEqualTo("改期后的记录");
    }

    @Test
    void updateCanChangeStageAndRoundNumber() {
        OpportunityRound round = OpportunityRound.create(USER, OPPORTUNITY, 1,
                RoundStage.HR, null, null, null);

        round.update(3, RoundStage.OA, null, null, null, null);

        assertThat(round.getRoundNumber()).isEqualTo(3);
        assertThat(round.getStage()).isEqualTo(RoundStage.OA);
    }

    @Test
    void updateRejectsInvalidRoundNumberBeforeChangingFields() {
        OpportunityRound round = OpportunityRound.create(USER, OPPORTUNITY, 1,
                RoundStage.HR, null, null, null);

        assertThatThrownBy(() -> round.update(0, RoundStage.OA, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(round.getRoundNumber()).isEqualTo(1);
        assertThat(round.getStage()).isEqualTo(RoundStage.HR);
    }

    @Test
    void updateCanChangeStage() {
        OpportunityRound round = OpportunityRound.create(USER, OPPORTUNITY, 1,
                RoundStage.HR, null, null, null);

        round.update(RoundStage.MANAGER, null, null, null);

        assertThat(round.getStage()).isEqualTo(RoundStage.MANAGER);
    }
}

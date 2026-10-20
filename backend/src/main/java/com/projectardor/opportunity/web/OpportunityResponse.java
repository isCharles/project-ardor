package com.projectardor.opportunity.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.domain.OpportunityRound;

public record OpportunityResponse(
        UUID id,
        String company,
        String targetRole,
        String platform,
        String status,
        String jdContent,
        String inviteContent,
        int currentRound,
        List<Round> rounds,
        Instant createdAt,
        Instant updatedAt) {

    public record Round(UUID id, int roundNumber, String stage, Instant scheduledAt, Instant occurredAt,
            Integer durationMinutes, String note) {
        public static Round from(OpportunityRound round) {
            return new Round(round.getId(), round.getRoundNumber(), round.getStage().name(),
                    round.getScheduledAt(), round.getOccurredAt(), round.getDurationMinutes(), round.getNote());
        }
    }

    public static OpportunityResponse from(JobOpportunity opportunity, List<OpportunityRound> rounds) {
        return from(com.projectardor.opportunity.service.InterviewProcessView.from(opportunity,rounds));
    }

    public static OpportunityResponse from(com.projectardor.opportunity.service.InterviewProcessView view) {
        return new OpportunityResponse(view.id(),view.company(),view.targetRole(),view.platform(),view.status(),
                view.jdContent(),view.inviteContent(),view.currentRound(),view.rounds().stream().map(round ->
                    new Round(round.id(),round.roundNumber(),round.stage(),round.scheduledAt(),round.occurredAt(),
                            round.durationMinutes(),round.note())).toList(),view.createdAt(),view.updatedAt());
    }
}

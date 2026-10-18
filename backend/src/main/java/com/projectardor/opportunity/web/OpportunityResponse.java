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
        int currentRound = rounds.stream().mapToInt(OpportunityRound::getRoundNumber).max().orElse(0);
        return new OpportunityResponse(
                opportunity.getId(),
                opportunity.getCompany(),
                opportunity.getTargetRole(),
                opportunity.getPlatform(),
                opportunity.getStatus().name(),
                opportunity.getJdContent(),
                opportunity.getInviteContent(),
                currentRound,
                rounds.stream().map(Round::from).toList(),
                opportunity.getCreatedAt(),
                opportunity.getUpdatedAt());
    }
}

package com.projectardor.opportunity.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.domain.OpportunityRound;

/** Owned application read model. A real interview, not a mock session or a transport response. */
public record InterviewProcessView(UUID id, String company, String targetRole, String platform, String status,
        String jdContent, String inviteContent, int currentRound, List<Round> rounds,
        Instant createdAt, Instant updatedAt) {
    public InterviewProcessView { rounds=List.copyOf(rounds); }

    public record Round(UUID id, int roundNumber, String stage, Instant scheduledAt, Instant occurredAt,
            Integer durationMinutes, String note) {
        static Round from(OpportunityRound round) {
            return new Round(round.getId(),round.getRoundNumber(),round.getStage().name(),round.getScheduledAt(),
                    round.getOccurredAt(),round.getDurationMinutes(),round.getNote());
        }
    }
    public static InterviewProcessView from(JobOpportunity process, List<OpportunityRound> rounds) {
        return new InterviewProcessView(process.getId(),process.getCompany(),process.getTargetRole(),process.getPlatform(),
                process.getStatus().name(),process.getJdContent(),process.getInviteContent(),
                rounds.stream().mapToInt(OpportunityRound::getRoundNumber).max().orElse(0),rounds.stream().map(Round::from).toList(),
                process.getCreatedAt(),process.getUpdatedAt());
    }
}

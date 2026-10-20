package com.projectardor.jobsearch.interview.web;

import java.time.Instant;

import jakarta.validation.constraints.Size;

/** roundNumber is optional on create (defaults to the next free round). */
public record OpportunityRoundRequest(
        Integer roundNumber,
        @Size(max = 32) String stage,
        Instant scheduledAt,
        Instant occurredAt,
        Integer durationMinutes,
        @Size(max = 2000) String note) {
    public com.projectardor.jobsearch.interview.service.InterviewProcessCommands.Round command() {
        return new com.projectardor.jobsearch.interview.service.InterviewProcessCommands.Round(roundNumber,stage,scheduledAt,occurredAt,durationMinutes,note);
    }
}

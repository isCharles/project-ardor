package com.projectardor.opportunity.web;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * targetRole carries the posting title the user sees in their notes. The invite
 * text and JD are optional: a process may be recorded before the details arrive.
 * Supplying a stage or a scheduled time creates the first round.
 */
public record OpportunityCreateRequest(
        @NotBlank @Size(max = 160) String company,
        @Size(max = 160) String targetRole,
        @Size(max = 80) String platform,
        @Size(max = 50000) String jdContent,
        @Size(max = 50000) String inviteContent,
        @Size(max = 32) String stage,
        Instant scheduledAt,
        Integer durationMinutes,
        @Size(max = 2000) String note) {}

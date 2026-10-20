package com.projectardor.opportunity.service;

import java.time.Instant;

/** Application inputs shared by HTTP and Agent adapters; no caller-supplied owner. */
public final class InterviewProcessCommands {
    private InterviewProcessCommands() {}

    public record Create(String company, String targetRole, String platform, String jdContent,
            String inviteContent, String stage, Instant scheduledAt, Integer durationMinutes, String note) {}
    /** Null fields are omitted; empty optional text explicitly clears the previous value. */
    public record Update(String company, String targetRole, String platform, String jdContent,
            String inviteContent, String status) {}
    /** Creating a round can omit its number; updating cannot change the existing number. */
    public record Round(Integer roundNumber, String stage, Instant scheduledAt, Instant occurredAt,
            Integer durationMinutes, String note) {}
}

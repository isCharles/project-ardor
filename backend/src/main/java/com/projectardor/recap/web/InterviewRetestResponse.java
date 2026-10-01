package com.projectardor.recap.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.calendar.domain.CalendarTask;

public record InterviewRetestResponse(UUID id, UUID attemptId, Instant dueAt, String challenge) {
    public static InterviewRetestResponse from(CalendarTask task) {
        return new InterviewRetestResponse(task.getId(), task.getReplayAttemptId(),
                task.getDueAt(), task.getDescription());
    }

    public record Overview(InterviewRetestResponse scheduled) {}
}

package com.projectardor.recap.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.recap.service.InterviewRecapQueueService;

public record InterviewRecapTaskResponse(
        UUID jobId, UUID recapId, String status, int attempts,
        String errorCode, String errorMessage, Instant createdAt, Instant finishedAt) {
    public static InterviewRecapTaskResponse from(InterviewRecapQueueService.TaskResult result) {
        return new InterviewRecapTaskResponse(result.jobId(), result.recapId(), result.status().name(),
                result.attempts(), result.errorCode(), result.errorMessage(), result.createdAt(), result.finishedAt());
    }
}

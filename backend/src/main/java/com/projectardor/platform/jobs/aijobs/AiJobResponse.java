package com.projectardor.platform.jobs.aijobs;

import java.time.Instant;
import java.util.UUID;

public record AiJobResponse(UUID id, AiJobType type, UUID resourceId, String status, int attempts,
        String resultPath, String errorCode, String errorMessage, Instant createdAt, Instant startedAt,
        Instant finishedAt, int estimateMinSeconds, int estimateMaxSeconds) {
    public static AiJobResponse from(AiJobStore.Job job) {
        return new AiJobResponse(job.id(), job.type(), job.resourceId(), job.status(), job.attempts(),
                job.resultPath(), job.errorCode(), job.errorMessage(), job.createdAt(), job.startedAt(), job.finishedAt(), 30, 120);
    }
}

package com.projectardor.platform.jobs.aijobs;

import java.time.Instant;
import java.util.UUID;

public record AiJobResponse(UUID id, AiJobType type, UUID resourceId, String status, int attempts, int maxAttempts,
        int priority, String resultPath, String errorCode, String errorMessage, Instant createdAt, Instant startedAt,
        Instant finishedAt, Instant retryAfter, int queuePosition, Integer estimateMinSeconds, Integer estimateMaxSeconds) {
    public static AiJobResponse from(AiJobStore.Job job, int queuePosition, AiJobStore.DurationSample sample) {
        boolean pending = "QUEUED".equals(job.status()) || "RUNNING".equals(job.status());
        Integer min = pending && sample != null ? sample.p50Seconds() : null;
        Integer max = pending && sample != null ? sample.p90Seconds() : null;
        return new AiJobResponse(job.id(), job.type(), job.resourceId(), job.status(), job.attempts(),
                job.maxAttempts(), job.priority(), job.resultPath(), job.errorCode(), job.errorMessage(),
                job.createdAt(), job.startedAt(), job.finishedAt(), job.retryAfter(), queuePosition, min, max);
    }
}

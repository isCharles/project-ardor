package com.projectardor.jobsearch.record.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.jobsearch.record.service.InterviewRecapQueueService;

public record InterviewRecapTaskResponse(
        UUID jobId, UUID recapId, String status, int attempts,
        String errorCode, String errorMessage, Instant createdAt, Instant finishedAt,
        String nextAction, String draftUrl) {
    public static InterviewRecapTaskResponse from(InterviewRecapQueueService.TaskResult result) {
        return new InterviewRecapTaskResponse(result.jobId(), result.recapId(), result.status().name(),
                result.attempts(), result.errorCode(), result.errorMessage(), result.createdAt(), result.finishedAt(),
                result.recapId() == null ? "原始转录已保存。初步纪要需用户校正补充并在界面确认后，才生成详细复盘。" : "打开已有复盘",
                result.jobId() == null ? null : "/app/recaps?draft=" + result.jobId());
    }
}

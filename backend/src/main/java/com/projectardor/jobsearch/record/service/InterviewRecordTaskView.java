package com.projectardor.jobsearch.record.service;

import java.time.Instant;
import java.util.UUID;

/** Compatibility projection of workflow progress; not an HTTP request or a new job state. */
public record InterviewRecordTaskView(UUID jobId, UUID recapId, String status, int attempts,
        String errorCode, String errorMessage, Instant createdAt, Instant finishedAt,
        String nextAction, String draftUrl) {
    public static InterviewRecordTaskView from(InterviewRecapQueueService.TaskResult result) {
        return new InterviewRecordTaskView(result.jobId(), result.recapId(), result.status().name(),
                result.attempts(), result.errorCode(), result.errorMessage(), result.createdAt(), result.finishedAt(),
                result.finalMinutes() ? "最终纪要已保存，后台正在规范化整理，可稍后查看结果。" : result.recapId() == null ? "原始转录已保存。初步纪要需用户校正补充并在界面确认后，才生成详细复盘。" : "打开已有复盘",
                result.jobId() == null ? null : "/app/recaps?" + (result.finalMinutes() ? "minutes=" : "draft=") + result.jobId());
    }
}

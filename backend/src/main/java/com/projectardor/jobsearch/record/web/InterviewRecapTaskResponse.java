package com.projectardor.jobsearch.record.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.jobsearch.record.service.InterviewRecapQueueService;
import com.projectardor.jobsearch.record.service.InterviewRecordTaskView;

public record InterviewRecapTaskResponse(
        UUID jobId, UUID recapId, String status, int attempts,
        String errorCode, String errorMessage, Instant createdAt, Instant finishedAt,
        String nextAction, String draftUrl) {
    public static InterviewRecapTaskResponse from(InterviewRecapQueueService.TaskResult result) {
        var view = InterviewRecordTaskView.from(result);
        return new InterviewRecapTaskResponse(view.jobId(), view.recapId(), view.status(), view.attempts(),
                view.errorCode(), view.errorMessage(), view.createdAt(), view.finishedAt(), view.nextAction(), view.draftUrl());
    }
}

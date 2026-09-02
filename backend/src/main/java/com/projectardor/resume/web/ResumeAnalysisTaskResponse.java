package com.projectardor.resume.web;

import java.util.UUID;

import com.projectardor.resume.domain.ResumeAnalysisJobStatus;
import com.projectardor.resume.service.ResumeAnalysisQueueService.TaskResult;

public record ResumeAnalysisTaskResponse(
        UUID jobId,
        UUID resumeId,
        ResumeAnalysisJobStatus status,
        UUID analysisId,
        int attempts,
        String errorCode,
        String errorMessage) {

    public static ResumeAnalysisTaskResponse from(TaskResult result) {
        return new ResumeAnalysisTaskResponse(
                result.jobId(), result.resumeId(), result.status(), result.analysisId(),
                result.attempts(), result.errorCode(), result.errorMessage());
    }
}

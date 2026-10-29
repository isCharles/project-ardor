package com.projectardor.materials.resume.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.materials.resume.service.ResumeView;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService.TaskResult;
import com.projectardor.materials.resume.domain.ResumeParseStatus;

public record ResumeResponse(
        UUID id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        ResumeParseStatus parseStatus,
        Instant createdAt,
        UUID analysisId,
        String analysisStatus,
        String analysisError,
        Integer overallScore,
        boolean primary,
        UUID displayAnalysisId) {

    public static ResumeResponse from(
            ResumeView resume, TaskResult task) {
        return new ResumeResponse(
                resume.id(), resume.originalFilename(), resume.contentType(),
                resume.sizeBytes(), resume.checksumSha256(), resume.parseStatus(),
                resume.createdAt(), resume.analysisId(),
                task == null ? null : task.status().name(),
                task == null ? null : task.errorMessage(),
                resume.overallScore(), resume.primary(), resume.displayAnalysisId());
    }
}

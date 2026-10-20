package com.projectardor.materials.resume.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.domain.ResumeAnalysis;
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
        String analysisError) {

    public static ResumeResponse from(
            Resume resume,
            ResumeAnalysis analysis,
            ResumeAnalysisTaskResponse task) {
        return new ResumeResponse(
                resume.getId(), resume.getOriginalFilename(), resume.getContentType(),
                resume.getSizeBytes(), resume.getChecksumSha256(), resume.getParseStatus(),
                resume.getCreatedAt(), analysis == null ? null : analysis.getId(),
                task == null ? null : task.status().name(),
                task == null ? null : task.errorMessage());
    }
}

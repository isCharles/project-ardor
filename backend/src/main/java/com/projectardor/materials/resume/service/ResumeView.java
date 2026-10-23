package com.projectardor.materials.resume.service;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.domain.ResumeAnalysis;
import com.projectardor.materials.resume.domain.ResumeParseStatus;

/** Owned detached metadata; never exposes storage keys, parsed source text or another owner. */
public record ResumeView(UUID id, String originalFilename, String contentType, long sizeBytes,
        String checksumSha256, ResumeParseStatus parseStatus, Instant createdAt,
        UUID analysisId, Integer overallScore) {
    public static ResumeView from(Resume resume, ResumeAnalysis analysis) {
        Integer score = null;
        if (analysis != null && analysis.getAnalysis().get("overallScore") instanceof Number value) {
            score = Math.max(0, Math.min(100, value.intValue()));
        }
        return new ResumeView(resume.getId(), resume.getOriginalFilename(), resume.getContentType(),
                resume.getSizeBytes(), resume.getChecksumSha256(), resume.getParseStatus(), resume.getCreatedAt(),
                analysis == null ? null : analysis.getId(), score);
    }
}

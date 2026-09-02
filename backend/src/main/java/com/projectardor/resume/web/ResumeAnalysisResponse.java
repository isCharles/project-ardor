package com.projectardor.resume.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.projectardor.resume.domain.ResumeAnalysis;

public record ResumeAnalysisResponse(
        UUID id,
        UUID resumeId,
        Map<String, Object> analysis,
        String modelName,
        String promptVersion,
        Instant createdAt) {

    public static ResumeAnalysisResponse from(ResumeAnalysis analysis) {
        return new ResumeAnalysisResponse(
                analysis.getId(), analysis.getResumeId(), analysis.getAnalysis(),
                analysis.getModelName(), analysis.getPromptVersion(), analysis.getCreatedAt());
    }
}

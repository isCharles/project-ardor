package com.projectardor.materials.resume.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.projectardor.materials.resume.domain.ResumeAnalysis;
import com.projectardor.materials.resume.service.ResumeAnalysisSnapshot;

public record ResumeAnalysisResponse(
        UUID id,
        UUID resumeId,
        Map<String, Object> analysis,
        String modelName,
        String promptVersion,
        Instant createdAt) {

    public static ResumeAnalysisResponse from(ResumeAnalysis analysis) {
        return fromSnapshot(ResumeAnalysisSnapshot.from(analysis));
    }

    public static ResumeAnalysisResponse fromSnapshot(ResumeAnalysisSnapshot analysis) {
        return new ResumeAnalysisResponse(
                analysis.id(), analysis.resumeId(), analysis.analysis(),
                analysis.modelName(), analysis.promptVersion(), analysis.createdAt());
    }
}

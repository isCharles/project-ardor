package com.projectardor.learning.practice.mockinterview.web;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAdaptiveInterviewRequest(
        UUID resumeAnalysisId,
        @Size(max = 160) String targetCompany,
        @NotBlank @Size(max = 160) String targetRole,
        UUID jobOpportunityId,
        @Size(max = 64) String seniority,
        @Min(1) Integer durationMinutes,
        boolean deepDive,
        @Size(max = 20_000) String jobDescription) {
}
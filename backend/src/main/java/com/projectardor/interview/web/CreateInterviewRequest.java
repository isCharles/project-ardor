package com.projectardor.interview.web;

import java.util.UUID;

import com.projectardor.interview.domain.InterviewModality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInterviewRequest(
        UUID resumeAnalysisId,
        InterviewModality modality,
        @Size(max = 160) String targetCompany,
        @NotBlank @Size(max = 160) String targetRole,
        @Min(3) @Max(10) Integer questionCount) {

    public int resolvedQuestionCount() {
        return questionCount == null ? 5 : questionCount;
    }

    public InterviewModality resolvedModality() {
        return modality == null ? InterviewModality.TEXT : modality;
    }
}

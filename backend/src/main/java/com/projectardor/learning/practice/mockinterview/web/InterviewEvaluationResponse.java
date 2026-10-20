package com.projectardor.learning.practice.mockinterview.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;

public record InterviewEvaluationResponse(
        UUID id,
        UUID interviewSessionId,
        BigDecimal overallScore,
        Map<String, Object> evaluation,
        String modelName,
        String promptVersion,
        Instant createdAt) {

    public static InterviewEvaluationResponse from(InterviewEvaluation evaluation) {
        return new InterviewEvaluationResponse(
                evaluation.getId(), evaluation.getInterviewSessionId(), evaluation.getOverallScore(),
                evaluation.getEvaluation(), evaluation.getModelName(),
                evaluation.getPromptVersion(), evaluation.getCreatedAt());
    }
}

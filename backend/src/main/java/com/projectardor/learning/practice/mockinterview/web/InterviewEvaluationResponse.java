package com.projectardor.learning.practice.mockinterview.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewEvaluationView;

public record InterviewEvaluationResponse(
        UUID id,
        UUID interviewSessionId,
        BigDecimal overallScore,
        Map<String, Object> evaluation,
        String modelName,
        String promptVersion,
        Instant createdAt) {

    public static InterviewEvaluationResponse from(InterviewEvaluation evaluation) {
        return fromView(MockInterviewEvaluationView.from(evaluation));
    }

    public static InterviewEvaluationResponse fromView(MockInterviewEvaluationView evaluation) {
        return new InterviewEvaluationResponse(
                evaluation.id(), evaluation.interviewSessionId(), evaluation.overallScore(),
                evaluation.evaluation(), evaluation.modelName(), evaluation.promptVersion(), evaluation.createdAt());
    }
}

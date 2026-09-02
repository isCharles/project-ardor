package com.projectardor.interview.web;

import java.util.List;
import java.util.UUID;

import com.projectardor.interview.domain.InterviewQuestion;

public record InterviewQuestionResponse(
        UUID id,
        int sequenceNumber,
        String questionText,
        String questionType,
        List<String> evaluationCriteria) {

    public static InterviewQuestionResponse from(InterviewQuestion question) {
        if (question == null) return null;
        return new InterviewQuestionResponse(
                question.getId(), question.getSequenceNumber(), question.getQuestionText(),
                question.getQuestionType(), question.getEvaluationCriteria());
    }
}

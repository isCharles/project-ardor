package com.projectardor.interview.web;

import java.util.List;
import java.util.UUID;

import com.projectardor.interview.domain.InterviewQuestion;
import com.projectardor.interview.service.LeetCodeHot100;

public record InterviewQuestionResponse(
        UUID id,
        int sequenceNumber,
        String questionText,
        String questionType,
        List<String> evaluationCriteria,
        LeetCodeProblem leetcode) {

    public record LeetCodeProblem(int id, String slug, String titleEn, String titleZh) {}

    public static InterviewQuestionResponse from(InterviewQuestion question) {
        if (question == null) return null;
        LeetCodeProblem leetcode = LeetCodeHot100.find(question.getLeetcodeSlug())
                .map(problem -> new LeetCodeProblem(problem.id(), problem.slug(), problem.titleEn(), problem.titleZh()))
                .orElse(null);
        return new InterviewQuestionResponse(
                question.getId(), question.getSequenceNumber(), question.getQuestionText(),
                question.getQuestionType(), question.getEvaluationCriteria(), leetcode);
    }
}

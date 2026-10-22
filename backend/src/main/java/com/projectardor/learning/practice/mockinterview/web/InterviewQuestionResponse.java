package com.projectardor.learning.practice.mockinterview.web;

import java.util.List;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewProgressView;

public record InterviewQuestionResponse(
        UUID id,
        int sequenceNumber,
        String questionText,
        String questionType,
        List<String> evaluationCriteria,
        LeetCodeProblem leetcode) {

    public record LeetCodeProblem(int id, String slug, String titleEn, String titleZh) {}

    public static InterviewQuestionResponse from(InterviewQuestion question) {
        return fromView(MockInterviewProgressView.Question.from(question));
    }

    public static InterviewQuestionResponse fromView(MockInterviewProgressView.Question question) {
        if (question == null) return null;
        var problem = question.leetcode();
        LeetCodeProblem leetcode = problem == null ? null
                : new LeetCodeProblem(problem.id(), problem.slug(), problem.titleEn(), problem.titleZh());
        return new InterviewQuestionResponse(
                question.id(), question.sequenceNumber(), question.questionText(),
                question.questionType(), question.evaluationCriteria(), leetcode);
    }
}

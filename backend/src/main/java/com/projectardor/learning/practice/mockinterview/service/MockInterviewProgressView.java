package com.projectardor.learning.practice.mockinterview.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;

public record MockInterviewProgressView(UUID sessionId, InterviewStatus status, int answeredCount,
        int totalQuestions, boolean readyToFinish, Question nextQuestion) {
    public static MockInterviewProgressView from(InterviewService.InterviewProgress progress) {
        var session = progress.session();
        return new MockInterviewProgressView(session.getId(), session.getStatus(), progress.answeredCount(),
                progress.totalQuestions(), session.getStatus() == InterviewStatus.IN_PROGRESS
                        && progress.nextQuestion() == null && progress.totalQuestions() > 0,
                Question.from(progress.nextQuestion()));
    }
    public record Question(UUID id, int sequenceNumber, String questionText, String questionType,
            List<String> evaluationCriteria, LeetCodeProblem leetcode) {
        public Question {
            evaluationCriteria = evaluationCriteria == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(evaluationCriteria));
        }
        public static Question from(InterviewQuestion question) {
            if (question == null) return null;
            var problem = LeetCodeHot100.find(question.getLeetcodeSlug()).map(item ->
                    new LeetCodeProblem(item.id(), item.slug(), item.titleEn(), item.titleZh())).orElse(null);
            return new Question(question.getId(), question.getSequenceNumber(), question.getQuestionText(),
                    question.getQuestionType(), question.getEvaluationCriteria(), problem);
        }
    }
    public record LeetCodeProblem(int id, String slug, String titleEn, String titleZh) {}
}

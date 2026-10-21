package com.projectardor.learning.practice.question.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.projectardor.jobsearch.record.domain.InterviewRecap;
import com.projectardor.jobsearch.record.domain.InterviewRecapQuestion;
import com.projectardor.learning.practice.question.domain.InterviewReplayAttempt;
import com.projectardor.learning.practice.question.domain.ReplayVerdict;

public record InterviewReplayResponse(UUID questionId, UUID recapId, String recapTitle, String questionText,
        String originalAnswer, String originalAssessment, String weaknessReason,
        List<Attempt> attempts) {
    public static InterviewReplayResponse from(com.projectardor.learning.practice.question.service.QuestionPracticeView view) {
        return new InterviewReplayResponse(view.questionId(),view.recapId(),view.recapTitle(),view.questionText(),
                view.originalAnswer(),view.originalAssessment(),view.weaknessReason(),
                view.attempts()==null ? null : view.attempts().stream().map(Attempt::from).toList());
    }
    public static InterviewReplayResponse from(InterviewRecapQuestion question, InterviewRecap recap,
            List<InterviewReplayAttempt> attempts) {
        return from(com.projectardor.learning.practice.question.service.QuestionPracticeView.from(question,recap,attempts));
    }

    public record Attempt(UUID id, UUID requestId, String answerText, ReplayVerdict verdict, String comparison,
            List<String> improvements, List<String> remainingGaps, String nextChallenge,
            String challengeText, Instant createdAt) {
        public static Attempt from(com.projectardor.learning.practice.question.service.QuestionPracticeView.Attempt view) {
            return new Attempt(view.id(),view.requestId(),view.answerText(),view.verdict(),view.comparison(),
                    view.improvements(),view.remainingGaps(),view.nextChallenge(),view.challengeText(),view.createdAt());
        }
        public static Attempt from(InterviewReplayAttempt attempt) {
            return from(com.projectardor.learning.practice.question.service.QuestionPracticeView.Attempt.from(attempt));
        }
    }
}

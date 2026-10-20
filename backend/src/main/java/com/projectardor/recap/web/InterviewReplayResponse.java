package com.projectardor.recap.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.projectardor.recap.domain.InterviewRecap;
import com.projectardor.recap.domain.InterviewRecapQuestion;
import com.projectardor.recap.domain.InterviewReplayAttempt;
import com.projectardor.recap.domain.ReplayVerdict;

public record InterviewReplayResponse(UUID questionId, UUID recapId, String recapTitle, String questionText,
        String originalAnswer, String originalAssessment, String weaknessReason,
        List<Attempt> attempts) {
    public static InterviewReplayResponse from(com.projectardor.recap.service.QuestionPracticeView view) {
        return new InterviewReplayResponse(view.questionId(),view.recapId(),view.recapTitle(),view.questionText(),
                view.originalAnswer(),view.originalAssessment(),view.weaknessReason(),
                view.attempts()==null ? null : view.attempts().stream().map(Attempt::from).toList());
    }
    public static InterviewReplayResponse from(InterviewRecapQuestion question, InterviewRecap recap,
            List<InterviewReplayAttempt> attempts) {
        return from(com.projectardor.recap.service.QuestionPracticeView.from(question,recap,attempts));
    }

    public record Attempt(UUID id, UUID requestId, String answerText, ReplayVerdict verdict, String comparison,
            List<String> improvements, List<String> remainingGaps, String nextChallenge,
            String challengeText, Instant createdAt) {
        public static Attempt from(com.projectardor.recap.service.QuestionPracticeView.Attempt view) {
            return new Attempt(view.id(),view.requestId(),view.answerText(),view.verdict(),view.comparison(),
                    view.improvements(),view.remainingGaps(),view.nextChallenge(),view.challengeText(),view.createdAt());
        }
        public static Attempt from(InterviewReplayAttempt attempt) {
            return from(com.projectardor.recap.service.QuestionPracticeView.Attempt.from(attempt));
        }
    }
}

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
    public static InterviewReplayResponse from(InterviewRecapQuestion question, InterviewRecap recap,
            List<InterviewReplayAttempt> attempts) {
        return new InterviewReplayResponse(question.getId(), recap.getId(), recap.getTitle(),
                question.getQuestionText(), question.getCandidateAnswer(), question.getAssessment(),
                question.getWeaknessReason(), attempts.stream().map(Attempt::from).toList());
    }

    public record Attempt(UUID id, UUID requestId, String answerText, ReplayVerdict verdict, String comparison,
            List<String> improvements, List<String> remainingGaps, String nextChallenge,
            String challengeText, Instant createdAt) {
        public static Attempt from(InterviewReplayAttempt attempt) {
            return new Attempt(attempt.getId(), attempt.getRequestId(), attempt.getAnswerText(),
                    attempt.getVerdict(), attempt.getComparison(), attempt.getImprovements(),
                    attempt.getRemainingGaps(), attempt.getNextChallenge(),
                    attempt.getChallengeText(), attempt.getCreatedAt());
        }
    }
}

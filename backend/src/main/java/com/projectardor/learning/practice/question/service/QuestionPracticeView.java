package com.projectardor.learning.practice.question.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.projectardor.jobsearch.record.domain.InterviewRecap;
import com.projectardor.jobsearch.record.domain.InterviewRecapQuestion;
import com.projectardor.learning.practice.question.domain.InterviewReplayAttempt;
import com.projectardor.learning.practice.question.domain.ReplayVerdict;

/** Practice-owned read model; not an HTTP response or a real interview round. */
public record QuestionPracticeView(UUID questionId,UUID recapId,String recapTitle,String questionText,
        String originalAnswer,String originalAssessment,String weaknessReason,List<Attempt> attempts) {
    public QuestionPracticeView { attempts=snapshot(attempts); }
    public static QuestionPracticeView from(InterviewRecapQuestion question,InterviewRecap recap,List<InterviewReplayAttempt> attempts) {
        return new QuestionPracticeView(question.getId(),recap.getId(),recap.getTitle(),question.getQuestionText(),
                question.getCandidateAnswer(),question.getAssessment(),question.getWeaknessReason(),
                attempts.stream().map(Attempt::from).toList());
    }
    public record Attempt(UUID id,UUID requestId,String answerText,ReplayVerdict verdict,String comparison,
            List<String> improvements,List<String> remainingGaps,String nextChallenge,String challengeText,Instant createdAt) {
        public Attempt { improvements=snapshot(improvements);remainingGaps=snapshot(remainingGaps); }
        public static Attempt from(InterviewReplayAttempt attempt) {
            return new Attempt(attempt.getId(),attempt.getRequestId(),attempt.getAnswerText(),attempt.getVerdict(),
                    attempt.getComparison(),attempt.getImprovements(),attempt.getRemainingGaps(),
                    attempt.getNextChallenge(),attempt.getChallengeText(),attempt.getCreatedAt());
        }
    }
    private static <T> List<T> snapshot(List<T> values) {
        return values==null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}

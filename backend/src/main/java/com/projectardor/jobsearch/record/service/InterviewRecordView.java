package com.projectardor.jobsearch.record.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.projectardor.jobsearch.record.domain.InterviewRecap;
import com.projectardor.jobsearch.record.domain.InterviewRecapQuestion;
import com.projectardor.jobsearch.record.domain.InterviewRecapSource;
import com.projectardor.learning.practice.question.domain.QuestionPerformance;

/** Owned real-interview record view shared by HTTP, Agent and background review. */
public record InterviewRecordView(UUID id, String title, InterviewRecapSource sourceType, String overview,
        List<String> strengths, List<String> weaknesses, String modelName, Instant createdAt,
        UUID jobOpportunityId, List<Question> questions, UUID interviewRoundId) {
    public InterviewRecordView {
        strengths=snapshot(strengths);weaknesses=snapshot(weaknesses);questions=snapshot(questions);
    }
    public static InterviewRecordView from(InterviewRecap recap,List<InterviewRecapQuestion> questions) {
        return new InterviewRecordView(recap.getId(),recap.getTitle(),recap.getSourceType(),recap.getOverview(),
                recap.getStrengths(),recap.getWeaknesses(),recap.getModelName(),recap.getCreatedAt(),
                recap.getJobOpportunityId(),questions.stream().map(Question::from).toList(),recap.getInterviewRoundId());
    }
    public record Question(UUID id,int sequenceNumber,String questionText,String candidateAnswer,
            List<String> followUps,String assessment,QuestionPerformance performance,String weaknessReason,
            String betterAnswer,List<String> tags) {
        public Question { followUps=snapshot(followUps);tags=snapshot(tags); }
        static Question from(InterviewRecapQuestion q) {
            return new Question(q.getId(),q.getSequenceNumber(),q.getQuestionText(),q.getCandidateAnswer(),
                    q.getFollowUps(),q.getAssessment(),q.getPerformance(),q.getWeaknessReason(),q.getBetterAnswer(),q.getTags());
        }
    }
    // Preserve legacy null/list-element semantics without exposing mutable domain lists.
    private static <T> List<T> snapshot(List<T> values) {
        return values==null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}

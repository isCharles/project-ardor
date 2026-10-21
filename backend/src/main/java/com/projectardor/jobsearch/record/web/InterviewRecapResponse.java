package com.projectardor.jobsearch.record.web;
import java.time.Instant;
import java.util.*;
import com.projectardor.jobsearch.record.domain.*;
import com.projectardor.learning.practice.question.domain.QuestionPerformance;
public record InterviewRecapResponse(UUID id, String title, InterviewRecapSource sourceType, String overview,
        List<String> strengths, List<String> weaknesses, String modelName, Instant createdAt,
        UUID jobOpportunityId, List<QuestionResponse> questions, UUID interviewRoundId) {
    public static InterviewRecapResponse from(com.projectardor.jobsearch.record.service.InterviewRecordView view) {
        return new InterviewRecapResponse(view.id(),view.title(),view.sourceType(),view.overview(),view.strengths(),
                view.weaknesses(),view.modelName(),view.createdAt(),view.jobOpportunityId(),
                view.questions()==null ? null : view.questions().stream().map(q -> new QuestionResponse(q.id(),q.sequenceNumber(),
                    q.questionText(),q.candidateAnswer(),q.followUps(),q.assessment(),q.performance(),q.weaknessReason(),q.betterAnswer(),q.tags())).toList(),
                view.interviewRoundId());
    }
    public static InterviewRecapResponse from(InterviewRecap recap, List<InterviewRecapQuestion> questions) {
        return from(com.projectardor.jobsearch.record.service.InterviewRecordView.from(recap,questions));
    }
    public record QuestionResponse(UUID id, int sequenceNumber, String questionText, String candidateAnswer,
            List<String> followUps, String assessment, QuestionPerformance performance, String weaknessReason,
            String betterAnswer, List<String> tags) {}
}

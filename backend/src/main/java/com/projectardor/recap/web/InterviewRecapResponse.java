package com.projectardor.recap.web;
import java.time.Instant;
import java.util.*;
import com.projectardor.recap.domain.*;
public record InterviewRecapResponse(UUID id, String title, String company, String targetRole, Instant occurredAt,
        InterviewRecapSource sourceType, String overview, List<String> strengths, List<String> weaknesses,
        String modelName, Instant createdAt, List<QuestionResponse> questions) {
    public static InterviewRecapResponse from(InterviewRecap recap, List<InterviewRecapQuestion> questions) {
        return new InterviewRecapResponse(recap.getId(), recap.getTitle(), recap.getCompany(), recap.getTargetRole(),
                recap.getOccurredAt(), recap.getSourceType(), recap.getOverview(), recap.getStrengths(), recap.getWeaknesses(),
                recap.getModelName(), recap.getCreatedAt(), questions.stream().map(QuestionResponse::from).toList());
    }
    public record QuestionResponse(UUID id, int sequenceNumber, String questionText, String candidateAnswer,
            List<String> followUps, String assessment, QuestionPerformance performance, String weaknessReason,
            String betterAnswer, List<String> tags) {
        static QuestionResponse from(InterviewRecapQuestion q) { return new QuestionResponse(q.getId(), q.getSequenceNumber(),
                q.getQuestionText(), q.getCandidateAnswer(), q.getFollowUps(), q.getAssessment(), q.getPerformance(),
                q.getWeaknessReason(), q.getBetterAnswer(), q.getTags()); }
    }
}

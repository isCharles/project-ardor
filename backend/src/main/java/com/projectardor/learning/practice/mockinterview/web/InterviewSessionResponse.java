package com.projectardor.learning.practice.mockinterview.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.service.MockInterviewSessionView;

public record InterviewSessionResponse(
        UUID id,
        UUID resumeAnalysisId,
        UUID jobOpportunityId,
        InterviewModality modality,
        String targetCompany,
        String targetRole,
        InterviewStatus status,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {

    public static InterviewSessionResponse from(InterviewSession session) {
        return fromView(MockInterviewSessionView.from(session));
    }

    public static InterviewSessionResponse fromView(MockInterviewSessionView session) {
        return new InterviewSessionResponse(
                session.id(), session.resumeAnalysisId(), session.jobOpportunityId(), session.modality(),
                session.targetCompany(), session.targetRole(), session.status(),
                session.startedAt(), session.finishedAt(), session.createdAt());
    }
}

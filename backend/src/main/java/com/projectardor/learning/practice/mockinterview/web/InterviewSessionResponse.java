package com.projectardor.learning.practice.mockinterview.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;

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
        return new InterviewSessionResponse(
                session.getId(), session.getResumeAnalysisId(), session.getJobOpportunityId(),
                session.getModality(),
                session.getTargetCompany(), session.getTargetRole(), session.getStatus(),
                session.getStartedAt(), session.getFinishedAt(), session.getCreatedAt());
    }
}

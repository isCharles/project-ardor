package com.projectardor.interview.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.interview.domain.InterviewModality;
import com.projectardor.interview.domain.InterviewSession;
import com.projectardor.interview.domain.InterviewStatus;

public record InterviewSessionResponse(
        UUID id,
        UUID resumeAnalysisId,
        InterviewModality modality,
        String targetCompany,
        String targetRole,
        InterviewStatus status,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {

    public static InterviewSessionResponse from(InterviewSession session) {
        return new InterviewSessionResponse(
                session.getId(), session.getResumeAnalysisId(), session.getModality(),
                session.getTargetCompany(), session.getTargetRole(), session.getStatus(),
                session.getStartedAt(), session.getFinishedAt(), session.getCreatedAt());
    }
}

package com.projectardor.learning.practice.mockinterview.service;

import java.time.Instant;
import java.util.UUID;
import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;

/** Mock practice application result, independent of HTTP and managed entity state. */
public record MockInterviewSessionView(UUID id, UUID resumeAnalysisId, UUID jobOpportunityId,
        InterviewModality modality, String targetCompany, String targetRole, InterviewStatus status,
        Instant startedAt, Instant finishedAt, Instant createdAt) {
    public static MockInterviewSessionView from(InterviewSession session) {
        return new MockInterviewSessionView(session.getId(), session.getResumeAnalysisId(), session.getJobOpportunityId(),
                session.getModality(), session.getTargetCompany(), session.getTargetRole(), session.getStatus(),
                session.getStartedAt(), session.getFinishedAt(), session.getCreatedAt());
    }
}

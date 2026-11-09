package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewCompetencyAssessment;

public interface InterviewCompetencyAssessmentRepository extends JpaRepository<InterviewCompetencyAssessment, UUID> {
    List<InterviewCompetencyAssessment> findAllByUserIdAndInterviewSessionId(UUID userId, UUID sessionId);
    Optional<InterviewCompetencyAssessment> findByInterviewSessionIdAndCompetencyAndUserId(
            UUID sessionId, String competency, UUID userId);
}
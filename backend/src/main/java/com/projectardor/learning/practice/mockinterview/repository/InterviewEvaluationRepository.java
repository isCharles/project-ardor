package com.projectardor.learning.practice.mockinterview.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;

public interface InterviewEvaluationRepository extends JpaRepository<InterviewEvaluation, UUID> {
    Optional<InterviewEvaluation> findByInterviewSessionIdAndUserId(UUID sessionId, UUID userId);
}

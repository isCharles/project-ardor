package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewTurnEvaluation;

public interface InterviewTurnEvaluationRepository extends JpaRepository<InterviewTurnEvaluation, UUID> {
    List<InterviewTurnEvaluation> findAllByUserIdAndInterviewSessionId(UUID userId, UUID sessionId);
    Optional<InterviewTurnEvaluation> findByInterviewQuestionIdAndUserId(UUID questionId, UUID userId);
    Optional<InterviewTurnEvaluation> findByInterviewAnswerIdAndUserId(UUID answerId, UUID userId);
}
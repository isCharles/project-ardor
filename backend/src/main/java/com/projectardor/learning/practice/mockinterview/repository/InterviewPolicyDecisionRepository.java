package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewPolicyDecision;

public interface InterviewPolicyDecisionRepository extends JpaRepository<InterviewPolicyDecision, UUID> {
    List<InterviewPolicyDecision> findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(UUID userId, UUID sessionId);
    long countByInterviewSessionIdAndUserId(UUID sessionId, UUID userId);
}
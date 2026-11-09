package com.projectardor.learning.practice.mockinterview.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewPlan;

public interface InterviewPlanRepository extends JpaRepository<InterviewPlan, UUID> {
    Optional<InterviewPlan> findByInterviewSessionIdAndUserId(UUID sessionId, UUID userId);
}
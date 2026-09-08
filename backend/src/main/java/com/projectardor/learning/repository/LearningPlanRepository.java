package com.projectardor.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.domain.LearningPlan;

public interface LearningPlanRepository extends JpaRepository<LearningPlan, UUID> {
    Optional<LearningPlan> findByIdAndUserId(UUID id, UUID userId);
    List<LearningPlan> findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(UUID userId);
}

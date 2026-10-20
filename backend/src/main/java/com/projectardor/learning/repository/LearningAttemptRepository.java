package com.projectardor.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.domain.LearningAttempt;

public interface LearningAttemptRepository extends JpaRepository<LearningAttempt, UUID> {
    Optional<LearningAttempt> findByUserIdAndRequestId(UUID userId, UUID requestId);
    List<LearningAttempt> findAllByUserIdAndPlanIdOrderByAttemptNoAsc(UUID userId, UUID planId);
}

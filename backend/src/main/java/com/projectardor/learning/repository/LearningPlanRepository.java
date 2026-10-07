package com.projectardor.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.learning.domain.LearningPlan;

public interface LearningPlanRepository extends JpaRepository<LearningPlan, UUID> {
    Optional<LearningPlan> findByIdAndUserId(UUID id, UUID userId);
    Optional<LearningPlan> findByUserIdAndRequestId(UUID userId, UUID requestId);
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':learning:' || CAST(:requestId AS text), 0))", nativeQuery = true)
    void lockRequestSlot(@Param("userId") UUID userId, @Param("requestId") UUID requestId);
    List<LearningPlan> findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(UUID userId);
}

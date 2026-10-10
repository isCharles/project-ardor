package com.projectardor.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.projectardor.learning.domain.LearningPlan;

public interface LearningPlanRepository extends JpaRepository<LearningPlan, UUID> {
    Optional<LearningPlan> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from LearningPlan plan where plan.id = :planId and plan.userId = :userId")
    Optional<LearningPlan> findOwnedForUpdate(@Param("planId") UUID planId, @Param("userId") UUID userId);
    Optional<LearningPlan> findByUserIdAndRequestId(UUID userId, UUID requestId);
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':learning:' || CAST(:requestId AS text), 0))", nativeQuery = true)
    void lockRequestSlot(@Param("userId") UUID userId, @Param("requestId") UUID requestId);
    List<LearningPlan> findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(UUID userId);
}

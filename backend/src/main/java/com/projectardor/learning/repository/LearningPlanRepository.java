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
    interface ProgressPlan {
        UUID getId(); String getConcept(); com.projectardor.learning.domain.LearningStatus getStatus();
        int getAttemptCount(); Integer getLastScore();
        com.projectardor.learning.domain.LearningOriginKind getOriginKind(); UUID getOriginResourceId(); UUID getOriginRecordId();
        com.projectardor.learning.domain.LearningSourceType getSourceType(); UUID getSourceId();
        UUID getTargetInterviewId(); UUID getTargetRoundId();
    }
    @Query("""
        select p.id as id,p.concept as concept,p.status as status,p.attemptCount as attemptCount,p.lastScore as lastScore,
          p.originKind as originKind,p.originResourceId as originResourceId,p.originRecordId as originRecordId,
          p.sourceType as sourceType,p.sourceId as sourceId,p.targetInterviewId as targetInterviewId,p.targetRoundId as targetRoundId
        from LearningPlan p where p.userId=:owner and (p.originResourceId in :sourceIds
          or (p.originKind is null and p.sourceType='RECAP' and p.sourceId in :sourceIds))
        order by p.createdAt desc,p.id
        """)
    List<ProgressPlan> progressPlans(@Param("owner") UUID owner,@Param("sourceIds") java.util.Collection<UUID> sourceIds);
    long countByUserId(UUID owner);
    Optional<LearningPlan> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select plan from LearningPlan plan where plan.id = :planId and plan.userId = :userId")
    Optional<LearningPlan> findOwnedForUpdate(@Param("planId") UUID planId, @Param("userId") UUID userId);
    Optional<LearningPlan> findByUserIdAndRequestId(UUID userId, UUID requestId);
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':learning:' || CAST(:requestId AS text), 0))", nativeQuery = true)
    void lockRequestSlot(@Param("userId") UUID userId, @Param("requestId") UUID requestId);
    List<LearningPlan> findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(UUID userId);
}

package com.projectardor.learning.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.learning.domain.LearningAttempt;

public interface LearningAttemptRepository extends JpaRepository<LearningAttempt, UUID> {
    Optional<LearningAttempt> findByUserIdAndRequestId(UUID userId, UUID requestId);
    List<LearningAttempt> findAllByUserIdAndPlanIdOrderByAttemptNoAsc(UUID userId, UUID planId);

    /**
     * Highest stored attempt number for one owned plan; 0 when the plan has no history yet.
     * Needed because anomalous legacy projections can backfill more rows than attempt_count,
     * so the next successor must be derived from stored history instead of attempt_count + 1.
     */
    @Query("select coalesce(max(attempt.attemptNo), 0) from LearningAttempt attempt"
            + " where attempt.userId = :userId and attempt.planId = :planId")
    int maxAttemptNo(@Param("userId") UUID userId, @Param("planId") UUID planId);
}

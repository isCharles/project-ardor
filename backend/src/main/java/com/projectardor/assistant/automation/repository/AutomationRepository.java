package com.projectardor.assistant.automation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.assistant.automation.domain.Automation;

public interface AutomationRepository extends JpaRepository<Automation, UUID> {
    List<Automation> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Automation> findByIdAndUserId(UUID id, UUID userId);
    List<Automation> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now);

    @Modifying
    @Query(value = """
            UPDATE automations SET last_run_at = :now, last_status = 'RUNNING', last_error = NULL,
                next_run_at = :nextRunAt, updated_at = :now
            WHERE id = :id AND user_id = :userId AND enabled = TRUE AND next_run_at <= :now
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now,
            @Param("nextRunAt") Instant nextRunAt);
}

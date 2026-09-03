package com.projectardor.recap.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.recap.domain.InterviewRecapJob;
import com.projectardor.recap.domain.InterviewRecapJobStatus;

public interface InterviewRecapJobRepository extends JpaRepository<InterviewRecapJob, UUID> {
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':' || :inputHash, 0))",
            nativeQuery = true)
    void lockRequestSlot(@Param("userId") UUID userId, @Param("inputHash") String inputHash);

    Optional<InterviewRecapJob> findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(
            UUID userId, String inputHash, Collection<InterviewRecapJobStatus> statuses);
    List<InterviewRecapJob> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<InterviewRecapJob> findByIdAndUserId(UUID id, UUID userId);

    @Query(value = """
            WITH next_job AS (
                SELECT id FROM interview_recap_jobs
                WHERE status = 'QUEUED' AND available_at <= CURRENT_TIMESTAMP
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE interview_recap_jobs jobs
            SET status = 'RUNNING', attempts = jobs.attempts + 1,
                started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            FROM next_job WHERE jobs.id = next_job.id
            RETURNING jobs.*
            """, nativeQuery = true)
    Optional<InterviewRecapJob> claimNext();

    @Modifying
    @Query("""
            update InterviewRecapJob job
            set job.status = com.projectardor.recap.domain.InterviewRecapJobStatus.QUEUED,
                job.availableAt = CURRENT_TIMESTAMP, job.startedAt = null,
                job.errorCode = 'WORKER_RECOVERED', job.errorMessage = '后台任务中断，已自动重新排队'
            where job.status = com.projectardor.recap.domain.InterviewRecapJobStatus.RUNNING
              and job.startedAt < :staleBefore
              and job.attempts < :maxAttempts
            """)
    int recoverStale(@Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("""
            update InterviewRecapJob job
            set job.status = com.projectardor.recap.domain.InterviewRecapJobStatus.FAILED,
                job.finishedAt = CURRENT_TIMESTAMP,
                job.errorCode = 'WORKER_RETRY_EXHAUSTED',
                job.errorMessage = '后台任务中断且已达到最大重试次数'
            where job.status = com.projectardor.recap.domain.InterviewRecapJobStatus.RUNNING
              and job.startedAt < :staleBefore
              and job.attempts >= :maxAttempts
            """)
    int failExhaustedStale(@Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts);
}

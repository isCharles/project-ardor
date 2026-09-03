package com.projectardor.resume.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.resume.domain.ResumeAnalysisJob;
import com.projectardor.resume.domain.ResumeAnalysisJobStatus;

public interface ResumeAnalysisJobRepository extends JpaRepository<ResumeAnalysisJob, UUID> {

    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':' || CAST(:resumeId AS text), 0))",
            nativeQuery = true)
    void lockRequestSlot(@Param("userId") UUID userId, @Param("resumeId") UUID resumeId);

    Optional<ResumeAnalysisJob> findByIdAndUserId(UUID id, UUID userId);

    Optional<ResumeAnalysisJob> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            UUID userId,
            UUID resumeId,
            Collection<ResumeAnalysisJobStatus> statuses);

    Optional<ResumeAnalysisJob> findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(UUID userId, UUID resumeId);

    @Query(value = """
            WITH next_job AS (
                SELECT id
                FROM resume_analysis_jobs
                WHERE status = 'QUEUED' AND available_at <= CURRENT_TIMESTAMP
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE resume_analysis_jobs jobs
            SET status = 'RUNNING',
                attempts = jobs.attempts + 1,
                started_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            FROM next_job
            WHERE jobs.id = next_job.id
            RETURNING jobs.*
            """, nativeQuery = true)
    Optional<ResumeAnalysisJob> claimNext();

    @Modifying
    @Query("""
            update ResumeAnalysisJob job
            set job.status = com.projectardor.resume.domain.ResumeAnalysisJobStatus.QUEUED,
                job.availableAt = CURRENT_TIMESTAMP,
                job.startedAt = null,
                job.errorCode = 'WORKER_RECOVERED',
                job.errorMessage = '后台任务中断，已自动重新排队'
            where job.status = com.projectardor.resume.domain.ResumeAnalysisJobStatus.RUNNING
              and job.startedAt < :staleBefore
              and job.attempts < :maxAttempts
            """)
    int recoverStale(@Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query("""
            update ResumeAnalysisJob job
            set job.status = com.projectardor.resume.domain.ResumeAnalysisJobStatus.FAILED,
                job.finishedAt = CURRENT_TIMESTAMP,
                job.errorCode = 'WORKER_RETRY_EXHAUSTED',
                job.errorMessage = '后台任务中断且已达到最大重试次数'
            where job.status = com.projectardor.resume.domain.ResumeAnalysisJobStatus.RUNNING
              and job.startedAt < :staleBefore
              and job.attempts >= :maxAttempts
            """)
    int failExhaustedStale(@Param("staleBefore") Instant staleBefore, @Param("maxAttempts") int maxAttempts);
}

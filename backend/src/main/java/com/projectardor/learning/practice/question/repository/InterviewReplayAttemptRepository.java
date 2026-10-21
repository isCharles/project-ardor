package com.projectardor.learning.practice.question.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.question.domain.InterviewReplayAttempt;

public interface InterviewReplayAttemptRepository extends JpaRepository<InterviewReplayAttempt, UUID> {
    interface ProgressReplay {
        UUID getRecapQuestionId(); long getAttemptCount(); UUID getLatestAttemptId();
        String getLatestVerdict(); java.time.Instant getLatestAt();
    }
    @org.springframework.data.jpa.repository.Query(value="""
        SELECT grouped.recap_question_id AS "recapQuestionId", grouped.attempt_count AS "attemptCount",
          latest.id AS "latestAttemptId", latest.verdict AS "latestVerdict", latest.created_at AS "latestAt"
        FROM (SELECT recap_question_id,count(*) AS attempt_count FROM interview_replay_attempts
          WHERE user_id=:owner AND recap_question_id IN (:questionIds) GROUP BY recap_question_id) grouped
        JOIN LATERAL (SELECT id,verdict,created_at FROM interview_replay_attempts
          WHERE user_id=:owner AND recap_question_id=grouped.recap_question_id
          ORDER BY created_at DESC,id DESC LIMIT 1) latest ON true
        """,nativeQuery=true)
    List<ProgressReplay> progressReplays(@org.springframework.data.repository.query.Param("owner") UUID owner,
            @org.springframework.data.repository.query.Param("questionIds") java.util.Collection<UUID> questionIds);
    long countByUserId(UUID owner);
    List<InterviewReplayAttempt> findAllByUserIdAndRecapQuestionIdOrderByCreatedAtDesc(UUID userId, UUID questionId);
    Optional<InterviewReplayAttempt> findByUserIdAndRequestId(UUID userId, UUID requestId);
    Optional<InterviewReplayAttempt> findByIdAndUserId(UUID id, UUID userId);
}

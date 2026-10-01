package com.projectardor.recap.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.recap.domain.InterviewReplayAttempt;

public interface InterviewReplayAttemptRepository extends JpaRepository<InterviewReplayAttempt, UUID> {
    List<InterviewReplayAttempt> findAllByUserIdAndRecapQuestionIdOrderByCreatedAtDesc(UUID userId, UUID questionId);
    Optional<InterviewReplayAttempt> findByUserIdAndRequestId(UUID userId, UUID requestId);
}

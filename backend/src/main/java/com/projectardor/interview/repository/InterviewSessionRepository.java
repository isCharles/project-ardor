package com.projectardor.interview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.interview.domain.InterviewSession;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    Optional<InterviewSession> findByIdAndUserId(UUID id, UUID userId);
    List<InterviewSession> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    Optional<InterviewSession> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InterviewSession s where s.id = :id and s.userId = :userId")
    Optional<InterviewSession> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
    List<InterviewSession> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

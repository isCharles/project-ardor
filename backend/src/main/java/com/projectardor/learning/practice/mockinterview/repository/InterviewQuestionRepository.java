package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {
    List<InterviewQuestion> findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(UUID userId, UUID sessionId);
    Optional<InterviewQuestion> findByIdAndUserIdAndInterviewSessionId(UUID id, UUID userId, UUID sessionId);
}

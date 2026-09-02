package com.projectardor.interview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.interview.domain.InterviewQuestion;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, UUID> {
    List<InterviewQuestion> findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(UUID userId, UUID sessionId);
    Optional<InterviewQuestion> findByIdAndUserIdAndInterviewSessionId(UUID id, UUID userId, UUID sessionId);
}

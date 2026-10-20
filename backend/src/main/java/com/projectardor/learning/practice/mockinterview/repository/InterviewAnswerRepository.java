package com.projectardor.learning.practice.mockinterview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.learning.practice.mockinterview.domain.InterviewAnswer;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswer, UUID> {
    List<InterviewAnswer> findAllByUserIdAndInterviewSessionId(UUID userId, UUID sessionId);
    Optional<InterviewAnswer> findByInterviewQuestionIdAndUserId(UUID questionId, UUID userId);
}

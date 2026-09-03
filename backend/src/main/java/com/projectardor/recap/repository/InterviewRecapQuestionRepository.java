package com.projectardor.recap.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.recap.domain.InterviewRecapQuestion;
public interface InterviewRecapQuestionRepository extends JpaRepository<InterviewRecapQuestion, UUID> {
    Optional<InterviewRecapQuestion> findByIdAndUserId(UUID id, UUID userId);
    List<InterviewRecapQuestion> findAllByUserIdAndRecapIdOrderBySequenceNumber(UUID userId, UUID recapId);
    List<InterviewRecapQuestion> findAllByUserIdOrderByRecapIdAscSequenceNumberAsc(UUID userId);
}

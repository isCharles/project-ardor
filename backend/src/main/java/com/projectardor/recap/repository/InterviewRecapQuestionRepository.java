package com.projectardor.recap.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.projectardor.recap.domain.InterviewRecapQuestion;
public interface InterviewRecapQuestionRepository extends JpaRepository<InterviewRecapQuestion, UUID> {
    Optional<InterviewRecapQuestion> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from InterviewRecapQuestion q where q.id = :id and q.userId = :userId")
    Optional<InterviewRecapQuestion> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
    List<InterviewRecapQuestion> findAllByUserIdAndRecapIdOrderBySequenceNumber(UUID userId, UUID recapId);
    List<InterviewRecapQuestion> findAllByUserIdOrderByRecapIdAscSequenceNumberAsc(UUID userId);
}

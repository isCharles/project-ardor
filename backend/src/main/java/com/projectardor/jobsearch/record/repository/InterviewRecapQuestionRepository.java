package com.projectardor.jobsearch.record.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.projectardor.jobsearch.record.domain.InterviewRecapQuestion;
public interface InterviewRecapQuestionRepository extends JpaRepository<InterviewRecapQuestion, UUID> {
    interface ProgressQuestion {
        UUID getId(); UUID getRecapId(); String getQuestionText(); String getObservation();
        com.projectardor.learning.practice.question.domain.QuestionPerformance getPerformance();
        String getRecapTitle(); java.time.Instant getRecapCreatedAt(); UUID getInterviewId(); UUID getRoundId();
    }
    @Query(value = """
        select q.id as id, q.recapId as recapId, q.questionText as questionText,
          substring(coalesce(nullif(q.weaknessReason,''),q.candidateAnswer,''),1,1200) as observation,
          q.performance as performance, r.title as recapTitle, r.createdAt as recapCreatedAt,
          r.jobOpportunityId as interviewId, r.interviewRoundId as roundId
        from InterviewRecapQuestion q join InterviewRecap r on r.id=q.recapId and r.userId=q.userId
        where q.userId=:owner and q.performance in :performances
          and (:sourceInterviewId is null or r.jobOpportunityId=:sourceInterviewId)
        order by r.createdAt desc,r.id,q.sequenceNumber,q.id
        """, countQuery = """
        select count(q) from InterviewRecapQuestion q join InterviewRecap r on r.id=q.recapId and r.userId=q.userId
        where q.userId=:owner and q.performance in :performances
          and (:sourceInterviewId is null or r.jobOpportunityId=:sourceInterviewId)
        """)
    org.springframework.data.domain.Page<ProgressQuestion> progressQuestions(@Param("owner") UUID owner,
            @Param("performances") Collection<com.projectardor.learning.practice.question.domain.QuestionPerformance> performances,
            @Param("sourceInterviewId") UUID sourceInterviewId, org.springframework.data.domain.Pageable pageable);
    Optional<InterviewRecapQuestion> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from InterviewRecapQuestion q where q.id = :id and q.userId = :userId")
    Optional<InterviewRecapQuestion> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
    List<InterviewRecapQuestion> findAllByUserIdAndRecapIdOrderBySequenceNumber(UUID userId, UUID recapId);
    List<InterviewRecapQuestion> findAllByUserIdOrderByRecapIdAscSequenceNumberAsc(UUID userId);
}

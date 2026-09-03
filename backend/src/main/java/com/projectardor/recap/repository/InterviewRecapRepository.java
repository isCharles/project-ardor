package com.projectardor.recap.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.projectardor.recap.domain.InterviewRecap;
public interface InterviewRecapRepository extends JpaRepository<InterviewRecap, UUID> {
    Optional<InterviewRecap> findByIdAndUserId(UUID id, UUID userId);
    Optional<InterviewRecap> findByUserIdAndInputHash(UUID userId, String inputHash);
    List<InterviewRecap> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Newest interview first, ordered by when the interview actually happened.
     * The LLM only fills occurredAt when the material states a date, so recaps
     * without one fall back to when they were filed.
     */
    @Query("""
            select recap from InterviewRecap recap
            where recap.userId = :userId
            order by coalesce(recap.occurredAt, recap.createdAt) desc, recap.createdAt desc
            """)
    List<InterviewRecap> findAllByUserIdOrderByInterviewTimeDesc(@Param("userId") UUID userId);
}

package com.projectardor.recap.repository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.recap.domain.*;
public interface MemoryCardRepository extends JpaRepository<MemoryCard, UUID> {
    Optional<MemoryCard> findByIdAndUserId(UUID id, UUID userId);
    Optional<MemoryCard> findByUserIdAndRecapQuestionId(UUID userId, UUID recapQuestionId);
    List<MemoryCard> findAllByUserIdOrderByNextReviewAtAscCreatedAtDesc(UUID userId);
    List<MemoryCard> findAllByUserIdAndStatusNotAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(UUID userId, MemoryCardStatus status, Instant dueAt);
    long countByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThan(
            UUID userId, MemoryCardStatus status, Instant from, Instant to);
    Optional<MemoryCard> findFirstByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThanOrderByNextReviewAtAsc(
            UUID userId, MemoryCardStatus status, Instant from, Instant to);
}

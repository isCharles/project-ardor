package com.projectardor.learning.review.repository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.learning.review.domain.MemoryCardReview;
public interface MemoryCardReviewRepository extends JpaRepository<MemoryCardReview, UUID> {}

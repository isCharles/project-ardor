package com.projectardor.recap.repository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.recap.domain.MemoryCardReview;
public interface MemoryCardReviewRepository extends JpaRepository<MemoryCardReview, UUID> {}

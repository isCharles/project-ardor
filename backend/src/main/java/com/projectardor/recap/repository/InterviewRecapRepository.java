package com.projectardor.recap.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import com.projectardor.recap.domain.InterviewRecap;
public interface InterviewRecapRepository extends JpaRepository<InterviewRecap, UUID> {
    Optional<InterviewRecap> findByIdAndUserId(UUID id, UUID userId);
    Optional<InterviewRecap> findByUserIdAndInputHash(UUID userId, String inputHash);
    List<InterviewRecap> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

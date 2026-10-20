package com.projectardor.materials.resume.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.materials.resume.domain.ResumeAnalysis;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, UUID> {
    Optional<ResumeAnalysis> findByIdAndUserId(UUID id, UUID userId);
    @Query(value = "SELECT * FROM resume_analyses WHERE resume_id=:resumeId AND user_id=:userId ORDER BY created_at DESC,id DESC LIMIT 1", nativeQuery = true)
    Optional<ResumeAnalysis> findByResumeIdAndUserId(@Param("resumeId") UUID resumeId, @Param("userId") UUID userId);
    Optional<ResumeAnalysis> findByResumeIdAndUserIdAndEvaluationKey(UUID resumeId, UUID userId, String evaluationKey);
    Optional<ResumeAnalysis> findFirstByUserIdAndEvaluationKeyOrderByCreatedAtAsc(UUID userId, String evaluationKey);
    List<ResumeAnalysis> findAllByResumeIdAndUserIdOrderByCreatedAtDesc(UUID resumeId, UUID userId);
    List<ResumeAnalysis> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

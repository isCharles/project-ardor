package com.projectardor.resume.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.resume.domain.ResumeAnalysis;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysis, UUID> {
    Optional<ResumeAnalysis> findByIdAndUserId(UUID id, UUID userId);
    Optional<ResumeAnalysis> findByResumeIdAndUserId(UUID resumeId, UUID userId);
    List<ResumeAnalysis> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

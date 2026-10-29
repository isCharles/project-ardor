package com.projectardor.materials.resume.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.materials.resume.domain.Resume;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);
    List<Resume> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
    boolean existsByUserIdAndPrimaryResumeTrue(UUID userId);
    List<Resume> findAllByUserIdAndPrimaryResumeTrue(UUID userId);

    @Modifying
    @Query("update Resume resume set resume.primaryResume = false where resume.userId = :userId and resume.primaryResume = true")
    void clearPrimary(@Param("userId") UUID userId);
}

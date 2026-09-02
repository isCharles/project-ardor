package com.projectardor.resume.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.resume.domain.Resume;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);
    List<Resume> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}

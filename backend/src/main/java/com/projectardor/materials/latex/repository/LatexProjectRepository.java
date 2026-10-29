package com.projectardor.materials.latex.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.materials.latex.domain.LatexProject;

public interface LatexProjectRepository extends JpaRepository<LatexProject, UUID> {
    Optional<LatexProject> findByIdAndUserId(UUID id, UUID userId);

    List<LatexProject> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}

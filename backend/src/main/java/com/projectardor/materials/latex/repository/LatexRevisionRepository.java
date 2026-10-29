package com.projectardor.materials.latex.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.materials.latex.domain.LatexRevision;

public interface LatexRevisionRepository extends JpaRepository<LatexRevision, UUID> {
    Optional<LatexRevision> findByProjectIdAndUserIdAndRevisionNo(UUID projectId, UUID userId, int revisionNo);

    List<LatexRevision> findByProjectIdAndUserIdOrderByRevisionNoDesc(UUID projectId, UUID userId);
}

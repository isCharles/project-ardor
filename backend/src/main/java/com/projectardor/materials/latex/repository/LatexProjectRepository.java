package com.projectardor.materials.latex.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.projectardor.materials.latex.domain.LatexProject;

import jakarta.persistence.LockModeType;

public interface LatexProjectRepository extends JpaRepository<LatexProject, UUID> {
    Optional<LatexProject> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Mutations take this row lock so a rename, archive or freeze cannot write a stale
     * currentRevision or archived_at back over a concurrent save.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LatexProject p where p.id = :projectId and p.userId = :userId")
    Optional<LatexProject> findByIdAndUserIdForUpdate(@Param("projectId") UUID projectId,
            @Param("userId") UUID userId);

    List<LatexProject> findByUserIdAndArchivedAtIsNullOrderByUpdatedAtDesc(UUID userId);

    List<LatexProject> findByUserIdAndArchivedAtIsNotNullOrderByUpdatedAtDesc(UUID userId);
}

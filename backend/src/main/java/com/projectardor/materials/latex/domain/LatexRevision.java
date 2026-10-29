package com.projectardor.materials.latex.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "latex_project_revisions")
public class LatexRevision {

    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "revision_no", nullable = false) private int revisionNo;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, String> files;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(length = 240) private String message;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected LatexRevision() {}

    public static LatexRevision create(UUID projectId, UUID userId, int revisionNo,
            Map<String, String> files, String sha256, String message) {
        LatexRevision revision = new LatexRevision();
        revision.id = UUID.randomUUID();
        revision.projectId = projectId;
        revision.userId = userId;
        revision.revisionNo = revisionNo;
        revision.files = Map.copyOf(files);
        revision.sha256 = sha256;
        revision.message = message;
        revision.createdAt = Instant.now();
        return revision;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getUserId() { return userId; }
    public int getRevisionNo() { return revisionNo; }
    public Map<String, String> getFiles() { return Map.copyOf(files); }
    public String getSha256() { return sha256; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}

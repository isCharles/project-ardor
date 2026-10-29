package com.projectardor.materials.latex.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "latex_projects")
public class LatexProject {

    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 160) private String title;
    @Column(name = "main_file", nullable = false, length = 200) private String mainFile;
    @Column(name = "current_revision", nullable = false) private int currentRevision;
    @Column(name = "frozen_revision") private Integer frozenRevision;
    @Column(name = "frozen_resume_id") private UUID frozenResumeId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "archived_at") private Instant archivedAt;

    protected LatexProject() {}

    public static LatexProject create(UUID userId, String title, String mainFile) {
        LatexProject project = new LatexProject();
        Instant now = Instant.now();
        project.id = UUID.randomUUID();
        project.userId = userId;
        project.title = title;
        project.mainFile = mainFile;
        project.currentRevision = 1;
        project.createdAt = now;
        project.updatedAt = now;
        return project;
    }

    public void advanceTo(int revision) {
        this.currentRevision = revision;
        this.updatedAt = Instant.now();
    }

    /** Renaming only changes the label; revisions and the frozen version stay untouched. */
    public void rename(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    /** Freezing is idempotent: freezing the same revision twice is not a second version. */
    public void freeze(int revision) {
        if (revision > currentRevision) throw new IllegalArgumentException("正式版本必须是已有修订");
        if (this.frozenRevision == null || this.frozenRevision != revision) this.frozenResumeId = null;
        this.frozenRevision = revision;
        this.updatedAt = Instant.now();
    }

    /** Records the uploaded PDF for the revision that is already frozen. */
    public void linkFrozenResume(int revision, UUID resumeId) {
        if (this.frozenRevision == null || this.frozenRevision != revision) {
            throw new IllegalArgumentException("只能关联已定稿版本的 PDF");
        }
        this.frozenResumeId = resumeId;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void archive() {
        if (archivedAt != null) return;
        archivedAt = Instant.now();
        updatedAt = archivedAt;
    }

    public void restore() {
        if (archivedAt == null) return;
        archivedAt = null;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getMainFile() { return mainFile; }
    public int getCurrentRevision() { return currentRevision; }
    public Integer getFrozenRevision() { return frozenRevision; }
    public UUID getFrozenResumeId() { return frozenResumeId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}

-- Server-side LaTeX resume workspace (docs/LOCAL_RESUME_WORKSPACE.md, phase 1): a project owns
-- immutable revisions of a small LaTeX package, and exactly one revision may be frozen as the
-- version the user is willing to send out. Nothing here touches a user's disk.
-- Rollback: DROP TABLE latex_project_revisions; DROP TABLE latex_projects;
CREATE TABLE latex_projects (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    main_file VARCHAR(200) NOT NULL,
    current_revision INTEGER NOT NULL,
    frozen_revision INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_latex_projects_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_latex_projects_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_latex_projects_user ON latex_projects (user_id, updated_at DESC);

CREATE TABLE latex_project_revisions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    revision_no INTEGER NOT NULL,
    files JSONB NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    message VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_latex_revisions_project_no UNIQUE (project_id, revision_no),
    CONSTRAINT fk_latex_revisions_project FOREIGN KEY (project_id) REFERENCES latex_projects (id) ON DELETE CASCADE
);
CREATE INDEX ix_latex_revisions_project ON latex_project_revisions (project_id, revision_no DESC);

-- V90: remember which uploaded resume came from a workspace's frozen revision.
-- Browser-local storage cannot identify the same PDF on another device, so the
-- association lives with the project. Deleting the resume clears the link.

ALTER TABLE latex_projects ADD COLUMN frozen_resume_id UUID;

ALTER TABLE latex_projects ADD CONSTRAINT fk_latex_projects_frozen_resume
    FOREIGN KEY (frozen_resume_id) REFERENCES resumes (id) ON DELETE SET NULL;

CREATE INDEX ix_latex_projects_frozen_resume ON latex_projects (frozen_resume_id);

COMMENT ON COLUMN latex_projects.frozen_resume_id IS
    'Resume uploaded from the frozen revision; NULL until the PDF is synced.';

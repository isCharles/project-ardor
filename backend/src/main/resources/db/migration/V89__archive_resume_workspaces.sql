-- V89: archive resume workspaces without deleting revisions. Archived
-- workspaces leave the list (and agent context) but stay recoverable.

ALTER TABLE latex_projects ADD COLUMN archived_at TIMESTAMPTZ;

COMMENT ON COLUMN latex_projects.archived_at IS
    'Soft archive: hidden from the workspace list while revisions stay intact.';

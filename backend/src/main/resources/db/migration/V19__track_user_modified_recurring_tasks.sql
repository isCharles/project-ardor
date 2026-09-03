-- Preserve a recurring occurrence that the user has edited when its series is cancelled.
-- V18 may already be installed, so this state marker is added by a forward-only migration.
ALTER TABLE tasks ADD COLUMN user_modified BOOLEAN NOT NULL DEFAULT FALSE;

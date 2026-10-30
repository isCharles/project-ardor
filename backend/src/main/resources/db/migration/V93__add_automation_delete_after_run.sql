-- One-time automations still use cron to choose their single run time. After a
-- successful run they are removed; after a failure they pause and stay visible
-- so the error can be inspected.
ALTER TABLE automations ADD COLUMN delete_after_run BOOLEAN NOT NULL DEFAULT FALSE;

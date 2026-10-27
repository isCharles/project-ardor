-- Accepted image turns must stay recoverable even when the run never completes.
ALTER TABLE agent_runs ADD COLUMN image_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

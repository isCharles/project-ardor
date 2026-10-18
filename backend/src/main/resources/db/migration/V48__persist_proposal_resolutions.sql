ALTER TABLE messages ADD COLUMN proposal_resolutions jsonb NOT NULL DEFAULT '{}'::jsonb;

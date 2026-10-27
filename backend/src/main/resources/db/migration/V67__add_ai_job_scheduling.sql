ALTER TABLE ai_jobs ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_jobs ADD COLUMN retry_after TIMESTAMPTZ;
ALTER TABLE ai_jobs ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 5;

DROP INDEX IF EXISTS ix_ai_jobs_queue;
CREATE INDEX ix_ai_jobs_queue ON ai_jobs (priority DESC, created_at)
    WHERE status = 'QUEUED' AND (retry_after IS NULL OR retry_after <= CURRENT_TIMESTAMP);

ALTER TABLE ai_jobs DROP CONSTRAINT ck_ai_jobs_status;
ALTER TABLE ai_jobs ADD CONSTRAINT ck_ai_jobs_status
    CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELED','DEAD'));

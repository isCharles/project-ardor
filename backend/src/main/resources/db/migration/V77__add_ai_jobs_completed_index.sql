-- The job status API derives a typical duration band from the most recent completed runs of each
-- job type. This partial index keeps that aggregate on an index scan instead of a full table scan.
CREATE INDEX IF NOT EXISTS ix_ai_jobs_completed ON ai_jobs (job_type, finished_at DESC) WHERE status = 'COMPLETED';

-- Rollback: DROP INDEX IF EXISTS ix_ai_jobs_completed;

CREATE TABLE ai_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_type VARCHAR(40) NOT NULL,
    resource_id UUID,
    request_hash CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts INTEGER NOT NULL DEFAULT 0,
    result_path VARCHAR(512),
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_jobs_type CHECK (job_type IN ('LEARNING_CREATE', 'LEARNING_EVALUATE', 'REPLAY_EVALUATE', 'INTERVIEW_EVALUATE'))
);
CREATE INDEX ix_ai_jobs_owner_created ON ai_jobs(user_id, created_at DESC);
CREATE INDEX ix_ai_jobs_queue ON ai_jobs(created_at) WHERE status = 'QUEUED';
CREATE UNIQUE INDEX uq_ai_jobs_open_resource ON ai_jobs(user_id, job_type, resource_id)
WHERE resource_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING');

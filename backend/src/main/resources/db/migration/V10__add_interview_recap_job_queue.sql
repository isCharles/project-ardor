CREATE TABLE interview_recap_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    recap_id UUID,
    input_hash CHAR(64) NOT NULL,
    raw_content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_interview_recap_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_recap_jobs_recap FOREIGN KEY (recap_id, user_id)
        REFERENCES interview_recaps (id, user_id) ON DELETE SET NULL (recap_id),
    CONSTRAINT ck_interview_recap_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_interview_recap_jobs_attempts CHECK (attempts >= 0)
);

CREATE UNIQUE INDEX uq_interview_recap_jobs_active
    ON interview_recap_jobs (user_id, input_hash)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_interview_recap_jobs_claim
    ON interview_recap_jobs (status, available_at, created_at);
CREATE INDEX ix_interview_recap_jobs_user_created
    ON interview_recap_jobs (user_id, created_at DESC);

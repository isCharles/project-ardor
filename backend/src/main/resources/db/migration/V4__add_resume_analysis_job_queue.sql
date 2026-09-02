CREATE TABLE resume_analysis_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    resume_id UUID NOT NULL,
    analysis_id UUID,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resume_analysis_jobs_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_resume_analysis_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_analysis_jobs_resume_owner FOREIGN KEY (resume_id, user_id)
        REFERENCES resumes (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_analysis_jobs_analysis_owner FOREIGN KEY (analysis_id, user_id)
        REFERENCES resume_analyses (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_resume_analysis_jobs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_resume_analysis_jobs_attempts CHECK (attempts >= 0)
);

CREATE UNIQUE INDEX uq_resume_analysis_jobs_active
    ON resume_analysis_jobs (user_id, resume_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_resume_analysis_jobs_claim
    ON resume_analysis_jobs (status, available_at, created_at);
CREATE INDEX ix_resume_analysis_jobs_user_resume
    ON resume_analysis_jobs (user_id, resume_id, created_at DESC);

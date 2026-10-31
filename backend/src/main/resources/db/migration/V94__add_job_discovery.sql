-- Job discovery keeps public posting facts separate from each user's inbox state.
-- A posting row never carries "saved" or "ignored"; that lives in job_posting_discoveries.
CREATE TABLE job_postings (
    id UUID PRIMARY KEY,
    canonical_key VARCHAR(500) NOT NULL,
    company VARCHAR(200) NOT NULL,
    title VARCHAR(300) NOT NULL,
    location VARCHAR(300),
    apply_url TEXT NOT NULL,
    source VARCHAR(200) NOT NULL,
    salary_text VARCHAR(300),
    experience_text VARCHAR(300),
    deadline_text VARCHAR(120),
    link_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_postings_canonical UNIQUE (canonical_key),
    CONSTRAINT ck_job_postings_link_status CHECK (link_status IN ('OPEN', 'CLOSED', 'RESTRICTED', 'UNKNOWN'))
);
CREATE INDEX ix_job_postings_seen ON job_postings (first_seen_at DESC);

-- Every URL that pointed at the same posting stays readable even when one source
-- becomes unreachable or carries tracking parameters.
CREATE TABLE job_posting_sources (
    posting_id UUID NOT NULL REFERENCES job_postings (id) ON DELETE CASCADE,
    source VARCHAR(200) NOT NULL,
    url TEXT NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (posting_id, source, url)
);

CREATE TABLE job_posting_discoveries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    posting_id UUID NOT NULL REFERENCES job_postings (id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    match_reason TEXT,
    match_evidence TEXT,
    discovery_query TEXT,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_posting_discoveries_owner UNIQUE (user_id, posting_id),
    CONSTRAINT ck_job_posting_discoveries_state CHECK (state IN ('NEW', 'SAVED', 'IGNORED'))
);
CREATE INDEX ix_job_posting_discoveries_inbox
    ON job_posting_discoveries (user_id, state, discovered_at DESC);

CREATE TABLE job_discovery_preferences (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    roles JSONB NOT NULL DEFAULT '[]',
    locations JSONB NOT NULL DEFAULT '[]',
    keywords JSONB NOT NULL DEFAULT '[]',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A run is business state for one search; the existing ai_jobs queue remains the
-- only execution queue. Counts and per-query errors support partial success.
CREATE TABLE job_discovery_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    roles JSONB NOT NULL,
    locations JSONB NOT NULL,
    keywords JSONB NOT NULL,
    queries JSONB NOT NULL DEFAULT '[]',
    queries_done INTEGER NOT NULL DEFAULT 0,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    new_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    error_count INTEGER NOT NULL DEFAULT 0,
    errors JSONB NOT NULL DEFAULT '[]',
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_job_discovery_runs_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED'))
);
CREATE INDEX ix_job_discovery_runs_owner ON job_discovery_runs (user_id, created_at DESC);

-- V33 constrains the queue's job types; add the new kind without rewriting that
-- already-applied migration.
ALTER TABLE ai_jobs DROP CONSTRAINT IF EXISTS ck_ai_jobs_type;
ALTER TABLE ai_jobs ADD CONSTRAINT ck_ai_jobs_type
    CHECK (job_type IN ('LEARNING_CREATE', 'LEARNING_EVALUATE', 'REPLAY_EVALUATE', 'INTERVIEW_EVALUATE', 'JOB_DISCOVERY'));

-- The shared background-task read model projects the existing ai_jobs row; only
-- the module label and title need discovery-specific values.
ALTER VIEW background_task_catalog RENAME TO background_task_catalog_before_discovery;
CREATE VIEW background_task_catalog AS
SELECT c.user_id, c.kind, c.id,
    CASE WHEN c.kind = 'JOB_DISCOVERY' THEN 'applications' ELSE c.module END AS module,
    CASE WHEN c.kind = 'JOB_DISCOVERY' THEN 'Job discovery' ELSE c.title END AS title,
    c.status, c.attempts, c.created_at, c.started_at, c.finished_at, c.error_code,
    c.result_path, c.resource_id, c.completion_token
FROM background_task_catalog_before_discovery c;

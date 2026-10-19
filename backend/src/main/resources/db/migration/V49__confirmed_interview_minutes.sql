-- Raw submissions remain immutable in interview_recap_jobs. No old reports are rewritten.
ALTER TABLE interview_recap_jobs ADD CONSTRAINT uq_recap_jobs_owner UNIQUE (id, user_id);
CREATE TABLE interview_minutes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    minutes TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    skill_version VARCHAR(64) NOT NULL DEFAULT 'interview-minutes-v1',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, user_id),
    FOREIGN KEY (id, user_id) REFERENCES interview_recap_jobs(id, user_id) ON DELETE CASCADE
);
CREATE TABLE interview_minutes_reviews (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    user_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    minutes TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    recap_id UUID,
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    UNIQUE (draft_id, revision),
    FOREIGN KEY (draft_id, user_id) REFERENCES interview_minutes(id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (recap_id, user_id) REFERENCES interview_recaps(id, user_id) ON DELETE SET NULL (recap_id)
);
CREATE INDEX ix_interview_minutes_user ON interview_minutes(user_id, updated_at DESC);
CREATE INDEX ix_minutes_reviews_queue ON interview_minutes_reviews(status, created_at);
CREATE INDEX ix_minutes_reviews_user ON interview_minutes_reviews(user_id, draft_id, revision DESC);

-- Preserve the existing projection and read tokens; add the new confirmation workflow.
ALTER VIEW background_task_catalog RENAME TO background_task_catalog_before_minutes;
CREATE VIEW background_task_catalog AS
SELECT c.user_id,c.kind,c.id,c.module,
    CASE WHEN c.kind='RECAP_ORGANIZE' AND c.resource_id IS NULL THEN 'Draft minutes · please review' ELSE c.title END AS title,
    c.status,c.attempts,c.created_at,c.started_at,c.finished_at,c.error_code,
    CASE WHEN c.kind='RECAP_ORGANIZE' AND c.resource_id IS NULL THEN '/app/recaps?draft=' || c.id ELSE c.result_path END AS result_path,
    CASE WHEN c.kind='RECAP_ORGANIZE' AND c.resource_id IS NULL THEN c.id ELSE c.resource_id END AS resource_id,
    c.completion_token
FROM background_task_catalog_before_minutes c
UNION ALL
SELECT r.user_id,'RECAP_REVIEW',r.id,'recaps','Confirmed interview review',r.status,r.attempts,
    r.created_at,r.started_at,r.finished_at,CASE WHEN r.status='FAILED' THEN 'REVIEW_FAILED' ELSE NULL END,
    CASE WHEN r.recap_id IS NULL THEN '/app/recaps?draft=' || r.draft_id ELSE '/app/recaps?selected=' || r.recap_id END,
    COALESCE(r.recap_id,r.draft_id),r.status || ':' || r.attempts || ':' || COALESCE(EXTRACT(EPOCH FROM r.finished_at)::text,'')
FROM interview_minutes_reviews r;

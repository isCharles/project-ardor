CREATE TABLE background_task_reads (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(40) NOT NULL,
    task_id UUID NOT NULL,
    completion_token VARCHAR(160) NOT NULL,
    read_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, kind, task_id)
);

CREATE TABLE knowledge_index_tasks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID NOT NULL UNIQUE,
    generation INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_id UUID,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (document_id, user_id) REFERENCES knowledge_documents(id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_knowledge_index_tasks_owner ON knowledge_index_tasks(user_id, created_at DESC);

-- A read model only: each existing worker remains the source of execution truth.
CREATE VIEW background_task_catalog AS
SELECT j.user_id, 'RESUME_ANALYSIS'::varchar AS kind, j.id, 'resumes'::varchar AS module,
       r.original_filename::text AS title, j.status::varchar AS status, j.attempts,
       j.created_at, j.started_at, j.finished_at, j.error_code::varchar AS error_code,
       ('/app/resumes?selected=' || j.resume_id)::text AS result_path, j.resume_id AS resource_id,
       (j.status || ':' || j.attempts || ':' || COALESCE(EXTRACT(EPOCH FROM j.finished_at)::text,''))::varchar AS completion_token
FROM resume_analysis_jobs j JOIN resumes r ON r.id=j.resume_id AND r.user_id=j.user_id
UNION ALL
SELECT j.user_id, 'RECAP_ORGANIZE', j.id, 'recaps', COALESCE(r.title,'Interview notes'),
       j.status, j.attempts, j.created_at, j.started_at, j.finished_at, j.error_code,
       CASE WHEN j.recap_id IS NULL THEN '/app/recaps' ELSE '/app/recaps?selected=' || j.recap_id END,
       j.recap_id, j.status || ':' || j.attempts || ':' || COALESCE(EXTRACT(EPOCH FROM j.finished_at)::text,'')
FROM interview_recap_jobs j LEFT JOIN interview_recaps r ON r.id=j.recap_id AND r.user_id=j.user_id
UNION ALL
SELECT j.user_id, j.job_type, j.id,
       CASE j.job_type WHEN 'REPLAY_EVALUATE' THEN 'evidence' WHEN 'INTERVIEW_EVALUATE' THEN 'interviews' ELSE 'learning' END,
       CASE WHEN j.job_type='LEARNING_CREATE' THEN COALESCE(j.payload->>'concept','Learning') ELSE j.job_type END,
       j.status, j.attempts, j.created_at, j.started_at, j.finished_at, j.error_code,
       j.result_path, CASE WHEN j.job_type='LEARNING_CREATE' THEN COALESCE(j.resource_id, substring(j.result_path from 'id=([0-9a-f-]{36})')::uuid) ELSE j.resource_id END,
       j.status || ':' || j.attempts || ':' || COALESCE(EXTRACT(EPOCH FROM j.finished_at)::text,'')
FROM ai_jobs j
UNION ALL
SELECT j.user_id, 'KNOWLEDGE_INDEX', j.id, 'knowledge', d.title,
       j.status, j.attempts, j.created_at, j.started_at, j.finished_at, j.error_code,
       '/app/knowledge', j.document_id, j.status || ':' || j.generation
FROM knowledge_index_tasks j JOIN knowledge_documents d ON d.id=j.document_id AND d.user_id=j.user_id;

-- Old terminal results are history, not brand-new notifications after upgrade.
INSERT INTO background_task_reads(user_id, kind, task_id, completion_token)
SELECT user_id, kind, id, completion_token FROM background_task_catalog
WHERE status IN ('COMPLETED','FAILED');

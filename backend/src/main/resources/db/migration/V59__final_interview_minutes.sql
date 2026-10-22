-- Existing jobs remain in their original workflow. New final-minute submissions
-- are already user-authored and do not require an ASR draft/confirmation stage.
ALTER TABLE interview_recap_jobs ADD COLUMN final_minutes BOOLEAN NOT NULL DEFAULT FALSE;

ALTER VIEW background_task_catalog RENAME TO background_task_catalog_before_final_minutes;
CREATE VIEW background_task_catalog AS
SELECT c.user_id,c.kind,c.id,c.module,
    CASE WHEN j.final_minutes THEN 'Interview minutes' ELSE c.title END AS title,
    c.status,c.attempts,c.created_at,c.started_at,c.finished_at,c.error_code,
    CASE WHEN j.final_minutes THEN '/app/recaps?minutes=' || j.id ELSE c.result_path END AS result_path,
    c.resource_id,c.completion_token
FROM background_task_catalog_before_final_minutes c
LEFT JOIN interview_recap_jobs j ON c.kind='RECAP_ORGANIZE' AND j.id=c.id AND j.user_id=c.user_id;

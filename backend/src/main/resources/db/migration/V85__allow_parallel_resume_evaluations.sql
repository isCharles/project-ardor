-- One resume can be scored against several job descriptions at the same time, so the slot that
-- keeps duplicate work must be per evaluation, not per resume.
ALTER TABLE resume_analysis_jobs ADD COLUMN evaluation_key VARCHAR(64);

-- Completed jobs already point at the report that carries the key; older rows stay NULL and are
-- read through the resume-wide lookup the service keeps for them.
UPDATE resume_analysis_jobs job
   SET evaluation_key = analysis.evaluation_key
  FROM resume_analyses analysis
 WHERE job.analysis_id = analysis.id
   AND job.user_id = analysis.user_id
   AND job.evaluation_key IS NULL;

DROP INDEX uq_resume_analysis_jobs_active;
CREATE UNIQUE INDEX uq_resume_analysis_jobs_active
    ON resume_analysis_jobs (user_id, resume_id, evaluation_key)
    WHERE status IN ('QUEUED', 'RUNNING') AND evaluation_key IS NOT NULL;
CREATE INDEX ix_resume_analysis_jobs_resume_key
    ON resume_analysis_jobs (user_id, resume_id, evaluation_key, created_at DESC);

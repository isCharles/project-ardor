-- Keep legacy reports and allow explicit assessments under different frozen conditions.
ALTER TABLE resume_analyses DROP CONSTRAINT uq_resume_analyses_user_resume;
ALTER TABLE resume_analyses ADD COLUMN evaluation_key VARCHAR(64);
CREATE UNIQUE INDEX uq_resume_analysis_evaluation
    ON resume_analyses (user_id, resume_id, evaluation_key) WHERE evaluation_key IS NOT NULL;
CREATE UNIQUE INDEX uq_resume_analysis_legacy
    ON resume_analyses (user_id, resume_id) WHERE evaluation_key IS NULL;
ALTER TABLE resume_analysis_jobs ADD COLUMN scoring_context JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Surviving owned reports are the cache; deleting the last report removes cached content too.
CREATE INDEX ix_resume_scoring_reuse ON resume_analyses(user_id, evaluation_key, created_at);
CREATE INDEX ix_resume_analysis_latest ON resume_analyses(user_id, resume_id, created_at DESC, id DESC);

-- The two preparation surfaces can point at the real process they belong to:
-- a targeted mock interview, and a calendar entry for the interview itself.
-- Both are optional and keep the row when the process is removed.
ALTER TABLE interview_sessions ADD COLUMN job_opportunity_id UUID;
ALTER TABLE interview_sessions ADD CONSTRAINT fk_interview_sessions_opportunity_owner
    FOREIGN KEY (job_opportunity_id, user_id) REFERENCES job_opportunities (id, user_id)
    ON DELETE SET NULL (job_opportunity_id);
CREATE INDEX ix_interview_sessions_user_opportunity
    ON interview_sessions (user_id, job_opportunity_id);

ALTER TABLE tasks ADD COLUMN job_opportunity_id UUID;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_opportunity_owner
    FOREIGN KEY (job_opportunity_id, user_id) REFERENCES job_opportunities (id, user_id)
    ON DELETE SET NULL (job_opportunity_id);
CREATE INDEX ix_tasks_user_opportunity ON tasks (user_id, job_opportunity_id);

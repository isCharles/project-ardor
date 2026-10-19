-- Historical records remain process-only. Never infer a round from dates or titles.
ALTER TABLE job_opportunity_rounds ADD CONSTRAINT uq_interview_round_context
    UNIQUE (id, opportunity_id, user_id);

ALTER TABLE interview_recap_jobs ADD COLUMN interview_round_id UUID;
ALTER TABLE interview_recap_jobs ADD CONSTRAINT fk_recap_job_round_context
    FOREIGN KEY (interview_round_id, job_opportunity_id, user_id)
    REFERENCES job_opportunity_rounds(id, opportunity_id, user_id)
    ON DELETE SET NULL (interview_round_id);
CREATE INDEX ix_recap_jobs_user_round ON interview_recap_jobs(user_id, interview_round_id);

ALTER TABLE interview_recaps ADD COLUMN interview_round_id UUID;
ALTER TABLE interview_recaps ADD CONSTRAINT fk_recap_round_context
    FOREIGN KEY (interview_round_id, job_opportunity_id, user_id)
    REFERENCES job_opportunity_rounds(id, opportunity_id, user_id)
    ON DELETE SET NULL (interview_round_id);
CREATE INDEX ix_recaps_user_round ON interview_recaps(user_id, interview_round_id);

-- Minutes and immutable review versions inherit context through their owned draft/job.
-- Questions inherit context through their owned report. No duplicate mutable parent IDs.
-- Removing a round only detaches this optional association; it never deletes source material.

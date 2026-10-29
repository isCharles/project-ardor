-- A user can keep several resumes and several reports per resume. The primary
-- resume is what the workbench headlines; the display report is what reloading
-- a resume opens. Neither changes history: other resumes and reports stay listed.
ALTER TABLE resumes ADD COLUMN primary_resume BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE resumes ADD COLUMN display_analysis_id UUID;

ALTER TABLE resumes
    ADD CONSTRAINT fk_resumes_display_analysis
    FOREIGN KEY (display_analysis_id) REFERENCES resume_analyses (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_resumes_primary_per_user ON resumes (user_id) WHERE primary_resume;

-- Existing users get the newest resume as their primary so the workbench keeps
-- showing what it showed before this migration.
UPDATE resumes SET primary_resume = TRUE
WHERE id IN (SELECT DISTINCT ON (user_id) id FROM resumes ORDER BY user_id, created_at DESC, id DESC);

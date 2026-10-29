-- The old application_daily_counts.count was presented as "applications", but it measured
-- initial outbound greetings. Keep every old value under that meaning; do not invent resume
-- sends for historical days.
ALTER TABLE application_daily_counts RENAME COLUMN count TO greeting_count;
ALTER TABLE application_daily_counts ALTER COLUMN greeting_count DROP NOT NULL;
ALTER TABLE application_daily_counts ADD COLUMN resume_count INTEGER;
ALTER TABLE application_daily_counts
    ADD CONSTRAINT application_daily_counts_resume_count_check CHECK (resume_count BETWEEN 0 AND 500),
    ADD CONSTRAINT application_daily_counts_recorded_check
        CHECK (greeting_count IS NOT NULL OR resume_count IS NOT NULL);
COMMENT ON COLUMN application_daily_counts.greeting_count IS
    'Initial outbound greetings. Legacy rows keep their old count here.';
COMMENT ON COLUMN application_daily_counts.resume_count IS
    'Resumes actually sent. NULL means not recorded (all legacy rows), never zero.';

-- The old weekly goal was measured against the old count, so preserving it as a greeting goal
-- is the only non-mixed interpretation. The resume goal starts unset.
ALTER TABLE application_rhythm_settings RENAME COLUMN weekly_goal TO weekly_resume_goal;
ALTER TABLE application_rhythm_settings ADD COLUMN weekly_greeting_goal INTEGER
    CHECK (weekly_greeting_goal BETWEEN 0 AND 500);
UPDATE application_rhythm_settings
SET weekly_greeting_goal = NULLIF(weekly_resume_goal, 0),
    weekly_resume_goal = 0
WHERE weekly_resume_goal <> 0;
COMMENT ON COLUMN application_rhythm_settings.weekly_resume_goal IS
    'Weekly goal for resumes actually sent; 0 disables it.';
COMMENT ON COLUMN application_rhythm_settings.weekly_greeting_goal IS
    'Optional weekly greeting goal; NULL or 0 disables it. Legacy goals moved here.';

ALTER TABLE interview_replay_attempts ADD COLUMN challenge_text TEXT;
ALTER TABLE interview_replay_attempts ADD COLUMN retest_task_id UUID;
ALTER TABLE interview_replay_attempts ADD CONSTRAINT ck_replay_retest_pair
    CHECK ((challenge_text IS NULL) = (retest_task_id IS NULL));
ALTER TABLE interview_replay_attempts
    ADD CONSTRAINT uq_replay_attempt_question_owner UNIQUE (id, user_id, recap_question_id);

ALTER TABLE tasks ADD COLUMN replay_question_id UUID;
ALTER TABLE tasks ADD COLUMN replay_attempt_id UUID;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_replay_question_owner
    FOREIGN KEY (replay_question_id, user_id)
    REFERENCES interview_recap_questions (id, user_id) ON DELETE CASCADE;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_replay_attempt_question_owner
    FOREIGN KEY (replay_attempt_id, user_id, replay_question_id)
    REFERENCES interview_replay_attempts (id, user_id, recap_question_id) ON DELETE CASCADE;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_retest_links CHECK (
    (task_kind = 'INTERVIEW_RETEST' AND replay_question_id IS NOT NULL AND replay_attempt_id IS NOT NULL)
    OR (task_kind <> 'INTERVIEW_RETEST' AND replay_question_id IS NULL AND replay_attempt_id IS NULL)
);

ALTER TABLE tasks DROP CONSTRAINT ck_tasks_kind;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_kind
    CHECK (task_kind IN ('GENERAL', 'MEMORY_REVIEW', 'LEARNING', 'INTERVIEW_RETEST'));

-- A completed/cancelled retest is history; only one upcoming retest per question.
CREATE UNIQUE INDEX uq_tasks_open_retest_question
    ON tasks (user_id, replay_question_id)
    WHERE task_kind = 'INTERVIEW_RETEST' AND status IN ('TODO', 'IN_PROGRESS');

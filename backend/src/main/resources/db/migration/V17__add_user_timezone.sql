ALTER TABLE user_profiles
    ADD COLUMN timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai';

-- Deleting a mock interview must not silently delete an independently useful calendar task.
ALTER TABLE tasks DROP CONSTRAINT fk_tasks_source_interview_owner;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_source_interview_owner
    FOREIGN KEY (source_interview_id, user_id)
    REFERENCES interview_sessions (id, user_id) ON DELETE SET NULL (source_interview_id);

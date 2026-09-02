ALTER TABLE tasks
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE tasks
    ADD CONSTRAINT ck_tasks_source CHECK (source IN ('MANUAL', 'AGENT'));

CREATE INDEX ix_tasks_user_due ON tasks (user_id, due_at);

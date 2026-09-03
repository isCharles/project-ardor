-- Recurring calendar work: a series holds the rule, and every occurrence is
-- still an ordinary row in tasks, so completing, editing and rendering one
-- week's 组会 keeps working exactly as it does for a one-off task.
CREATE TABLE task_series (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    source VARCHAR(32) NOT NULL DEFAULT 'AGENT',
    timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
    frequency VARCHAR(32) NOT NULL,
    interval_value INTEGER NOT NULL DEFAULT 1,
    by_weekdays VARCHAR(64),
    by_month_day INTEGER,
    time_of_day TIME NOT NULL DEFAULT '09:00',
    start_date DATE NOT NULL,
    until_date DATE,
    occurrence_limit INTEGER,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    materialized_through DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_task_series_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_task_series_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_task_series_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_task_series_status CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT ck_task_series_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_task_series_source CHECK (source IN ('MANUAL', 'AGENT')),
    CONSTRAINT ck_task_series_interval CHECK (interval_value BETWEEN 1 AND 52),
    CONSTRAINT ck_task_series_month_day CHECK (by_month_day IS NULL OR by_month_day BETWEEN 1 AND 31),
    CONSTRAINT ck_task_series_limit CHECK (occurrence_limit IS NULL OR occurrence_limit BETWEEN 1 AND 500),
    CONSTRAINT ck_task_series_window CHECK (until_date IS NULL OR until_date >= start_date)
);

CREATE INDEX ix_task_series_user_status ON task_series (user_id, status);

ALTER TABLE tasks ADD COLUMN series_id UUID;
ALTER TABLE tasks ADD COLUMN occurrence_date DATE;

-- Dropping a series must never take completed history with it: the occurrence
-- rows survive as ordinary tasks with the link cleared.
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_series_owner
    FOREIGN KEY (series_id, user_id)
    REFERENCES task_series (id, user_id) ON DELETE SET NULL (series_id);

ALTER TABLE tasks ADD CONSTRAINT ck_tasks_series_occurrence
    CHECK ((series_id IS NULL) = (occurrence_date IS NULL));

-- One row per date per series, so re-running materialization is a no-op.
CREATE UNIQUE INDEX uq_tasks_series_occurrence
    ON tasks (series_id, occurrence_date)
    WHERE series_id IS NOT NULL;

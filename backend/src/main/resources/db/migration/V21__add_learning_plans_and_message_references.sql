CREATE TABLE learning_plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    concept VARCHAR(160) NOT NULL,
    reason TEXT,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID,
    status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    lesson JSONB NOT NULL DEFAULT '{}'::jsonb,
    exercises JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_evaluation JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_score INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMPTZ NOT NULL,
    next_review_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    model_name VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_learning_plans_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_learning_plans_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_learning_plans_source CHECK (source_type IN ('MANUAL', 'AGENT', 'RESUME', 'RECAP', 'KNOWLEDGE')),
    CONSTRAINT ck_learning_plans_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'NEEDS_REVIEW', 'COMPLETED')),
    CONSTRAINT ck_learning_plans_score CHECK (last_score IS NULL OR last_score BETWEEN 0 AND 100),
    CONSTRAINT ck_learning_plans_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_learning_plans_user_schedule ON learning_plans (user_id, status, scheduled_at);

ALTER TABLE tasks ADD COLUMN learning_plan_id UUID;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_learning_plan_owner
    FOREIGN KEY (learning_plan_id, user_id) REFERENCES learning_plans (id, user_id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_tasks_learning_plan ON tasks (learning_plan_id) WHERE learning_plan_id IS NOT NULL;

ALTER TABLE tasks DROP CONSTRAINT ck_tasks_kind;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_kind CHECK (task_kind IN ('GENERAL', 'MEMORY_REVIEW', 'LEARNING'));

ALTER TABLE messages ADD COLUMN context_references JSONB NOT NULL DEFAULT '[]'::jsonb;

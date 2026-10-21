-- Automation tasks: a user (or Ardor, acting on the user's request) schedules a
-- prompt to be sent to the agent on a recurring schedule. The prompt is stored
-- server-side and hidden from the conversation transcript; the assistant reply
-- remains visible.
CREATE TABLE automations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    prompt TEXT NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_status VARCHAR(32),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_automations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_automations_title CHECK (length(trim(title)) BETWEEN 1 AND 160),
    CONSTRAINT ck_automations_prompt CHECK (length(trim(prompt)) BETWEEN 1 AND 20000)
);

CREATE INDEX ix_automations_due ON automations (enabled, next_run_at);
CREATE INDEX ix_automations_user_created ON automations (user_id, created_at DESC);

ALTER TABLE messages ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE agent_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    label VARCHAR(240) NOT NULL DEFAULT '正在理解你的请求',
    tool_started BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(80),
    error_message TEXT,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    assistant_message_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_runs_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_agent_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_runs_conversation_owner FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_runs_assistant_owner FOREIGN KEY (assistant_message_id, user_id)
        REFERENCES messages (id, user_id) ON DELETE SET NULL (assistant_message_id),
    CONSTRAINT ck_agent_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED'))
);

CREATE INDEX ix_agent_runs_user_conversation_created
    ON agent_runs (user_id, conversation_id, created_at DESC);

CREATE TABLE agent_learning_action_slots (
    request_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    logical_action_id UUID NOT NULL,
    semantic_key CHAR(64) NOT NULL,
    call_signature CHAR(64) NOT NULL,
    origin_run_id UUID NOT NULL,
    last_used_run_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_learning_action_signature
        UNIQUE (user_id, logical_action_id, semantic_key, call_signature)
);

CREATE INDEX ix_agent_learning_action_slots
    ON agent_learning_action_slots (user_id, logical_action_id, semantic_key);

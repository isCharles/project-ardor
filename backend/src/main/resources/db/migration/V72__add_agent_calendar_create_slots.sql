-- Semantic identity for Agent-created calendar tasks, so a committed create with a
-- lost response is returned instead of creating a duplicate on retry. Raw JSON
-- argument text is not an identity; semantic_key is built from normalized fields.
CREATE TABLE agent_calendar_create_slots (
    request_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    logical_action_id UUID NOT NULL,
    semantic_key CHAR(64) NOT NULL,
    call_signature CHAR(64) NOT NULL,
    origin_run_id UUID NOT NULL,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_calendar_action_semantic
        UNIQUE (user_id, logical_action_id, semantic_key)
);

CREATE INDEX ix_agent_calendar_action_slots
    ON agent_calendar_create_slots (user_id, logical_action_id, semantic_key);

CREATE TABLE agent_message_queue (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    sequence BIGSERIAL NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    blocked_by UUID,
    error_message VARCHAR(400),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_queue_conversation_owner FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversations(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_queue_status CHECK (status IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED'))
);
CREATE INDEX ix_agent_queue_owner_sequence ON agent_message_queue(user_id, sequence);
CREATE INDEX ix_agent_queue_pending ON agent_message_queue(status, sequence)
    WHERE status IN ('QUEUED', 'DISPATCHING', 'RUNNING');

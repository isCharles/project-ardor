-- V63: durable rolling conversation summaries used to compact the prompt.
-- Raw messages remain in `messages`; a summary only changes the prompt view.

CREATE TABLE conversation_summaries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    covered_message_id UUID NOT NULL,
    covered_count INTEGER NOT NULL CHECK (covered_count > 0),
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL CHECK (token_count >= 0),
    model VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conversation_id, version)
);

CREATE INDEX ix_conversation_summaries_owner
    ON conversation_summaries (user_id, conversation_id, version DESC);

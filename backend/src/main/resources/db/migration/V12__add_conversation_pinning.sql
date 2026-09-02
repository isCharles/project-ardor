ALTER TABLE conversations
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_conversations_user_pinned_updated
    ON conversations (user_id, pinned DESC, updated_at DESC);

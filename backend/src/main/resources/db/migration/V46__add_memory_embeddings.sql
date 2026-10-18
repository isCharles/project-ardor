-- Semantic retrieval for long-term memory, mirroring the knowledge-base embedding model.
-- The column stays dimension-less because every user brings their own embedding endpoint;
-- each row records its dimension and model, and similarity queries filter on both.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE user_agent_memory_items
    ADD COLUMN embedding vector,
    ADD COLUMN embedding_dim INTEGER,
    ADD COLUMN embedding_model VARCHAR(160),
    ADD COLUMN embedded_at TIMESTAMPTZ;

ALTER TABLE user_agent_memory_items
    ADD CONSTRAINT ck_agent_memory_item_embedding_complete
        CHECK ((embedding IS NULL AND embedding_dim IS NULL)
            OR (embedding IS NOT NULL AND embedding_dim > 0));

CREATE INDEX ix_agent_memory_item_pending_embedding
    ON user_agent_memory_items (user_id, created_at)
    WHERE embedding IS NULL AND valid_until IS NULL;

CREATE INDEX ix_agent_memory_item_user_dim
    ON user_agent_memory_items (user_id, embedding_dim)
    WHERE embedding IS NOT NULL;

-- Semantic retrieval for the knowledge base.
--
-- The embedding column is intentionally dimension-less: every user brings their own
-- embedding endpoint (auxiliary_api_configs.service_type = 'EMBEDDING'), and different
-- models emit different dimensions. Each row therefore records the dimension it was
-- produced with, and every similarity query filters on (user_id, embedding_dim) so that
-- only comparable vectors ever reach the distance operator.
--
-- A dimension-less column cannot carry an HNSW/IVFFlat index. Exact KNN after the
-- user_id filter is correct and fast at the per-user chunk counts this app produces.
-- Once a deployment standardises on one embedding model, pin the dimension and add:
--   CREATE INDEX ix_knowledge_chunks_embedding ON knowledge_chunks
--       USING hnsw ((embedding::vector(1536)) vector_cosine_ops);
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE knowledge_chunks
    ADD COLUMN embedding vector,
    ADD COLUMN embedding_dim INTEGER,
    ADD COLUMN embedding_model VARCHAR(160),
    ADD COLUMN embedded_at TIMESTAMPTZ;

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT ck_knowledge_chunks_embedding_complete
        CHECK ((embedding IS NULL AND embedding_dim IS NULL)
            OR (embedding IS NOT NULL AND embedding_dim > 0));

-- Drives the "which chunks still need embedding" backfill scan.
CREATE INDEX ix_knowledge_chunks_pending_embedding
    ON knowledge_chunks (user_id, created_at)
    WHERE embedding IS NULL;

-- Drives the user-scoped similarity search; user_id is always the leading filter.
CREATE INDEX ix_knowledge_chunks_user_dim
    ON knowledge_chunks (user_id, embedding_dim)
    WHERE embedding IS NOT NULL;

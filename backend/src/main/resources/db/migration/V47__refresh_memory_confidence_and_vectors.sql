-- Earlier extraction discarded candidate confidence and left it at 1.0.
-- The original estimate cannot be recovered; use a conservative inferred default.
UPDATE user_agent_memory_items SET confidence = 0.7
WHERE source_kind = 'INFERRED' AND confidence = 1.0;

-- Existing vectors may predate an in-place edit. Backfill from current content.
UPDATE user_agent_memory_items
SET embedding = NULL, embedding_dim = NULL, embedding_model = NULL, embedded_at = NULL
WHERE embedding IS NOT NULL;

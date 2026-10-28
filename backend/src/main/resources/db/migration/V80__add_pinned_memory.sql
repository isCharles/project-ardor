-- Core memories are pinned to every context, so a turn needs no embedding call to keep
-- stable background, goals and preferences available. No existing row is pinned here:
-- promoting a memory stays an explicit user action in the memory panel.
-- Rollback: DROP INDEX ix_agent_memory_item_pinned; ALTER TABLE user_agent_memory_items DROP COLUMN pinned;
ALTER TABLE user_agent_memory_items ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_agent_memory_item_pinned ON user_agent_memory_items (user_id, created_at)
    WHERE pinned AND valid_until IS NULL;

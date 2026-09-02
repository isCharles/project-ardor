CREATE TABLE user_agent_memory_items (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_agent_memory_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_agent_memory_items_content CHECK (char_length(content) BETWEEN 1 AND 4000)
);
CREATE INDEX ix_user_agent_memory_items_user_created ON user_agent_memory_items (user_id, created_at);

INSERT INTO user_agent_memory_items (id, user_id, content, created_at, updated_at)
SELECT gen_random_uuid(), user_id, content, created_at, updated_at
FROM user_agent_memories WHERE btrim(content) <> '';

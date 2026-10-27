ALTER TABLE messages ADD COLUMN image_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE chat_images (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    message_id UUID,
    media_type VARCHAR(32) NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_images_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_images_message_owner FOREIGN KEY (message_id, user_id)
        REFERENCES messages (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_images_media_type CHECK (media_type IN ('image/png', 'image/jpeg', 'image/webp', 'image/gif')),
    CONSTRAINT ck_chat_images_size CHECK (octet_length(content) BETWEEN 1 AND 8388608)
);
CREATE INDEX ix_chat_images_user_created ON chat_images (user_id, created_at DESC);
CREATE INDEX ix_chat_images_orphan_created ON chat_images (created_at) WHERE message_id IS NULL;

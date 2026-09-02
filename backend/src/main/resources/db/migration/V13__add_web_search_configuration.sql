CREATE TABLE web_search_configs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    encrypted_api_key BYTEA NOT NULL,
    api_key_iv BYTEA NOT NULL,
    key_hint VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_web_search_configs_user UNIQUE (user_id),
    CONSTRAINT fk_web_search_configs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

ALTER TABLE users
    ADD CONSTRAINT ck_users_email_normalized
        CHECK (email = LOWER(BTRIM(email)));

CREATE TABLE llm_provider_configs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(160) NOT NULL,
    encrypted_api_key BYTEA NOT NULL,
    api_key_iv BYTEA NOT NULL,
    key_hint VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_llm_provider_configs_user UNIQUE (user_id),
    CONSTRAINT uq_llm_provider_configs_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_llm_provider_configs_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_llm_provider_configs_provider
        CHECK (provider IN ('OPENAI', 'OPENAI_COMPATIBLE'))
);

CREATE INDEX ix_llm_provider_configs_user
    ON llm_provider_configs (user_id);

CREATE TABLE auxiliary_api_configs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    service_type VARCHAR(32) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(160) NOT NULL,
    encrypted_api_key BYTEA NOT NULL,
    api_key_iv BYTEA NOT NULL,
    key_hint VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_auxiliary_api_configs_user_type UNIQUE (user_id, service_type),
    CONSTRAINT fk_auxiliary_api_configs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_auxiliary_api_configs_type CHECK (service_type IN ('EMBEDDING', 'ASR', 'TTS', 'FALLBACK_LLM'))
);

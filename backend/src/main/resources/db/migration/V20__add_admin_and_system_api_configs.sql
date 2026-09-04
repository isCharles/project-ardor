ALTER TABLE users ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

CREATE TABLE system_api_configs (
    service_type VARCHAR(32) PRIMARY KEY,
    provider VARCHAR(120) NOT NULL,
    base_url VARCHAR(512),
    model VARCHAR(160),
    encrypted_api_key BYTEA NOT NULL,
    api_key_iv BYTEA NOT NULL,
    key_hint VARCHAR(16) NOT NULL,
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_api_service_type CHECK (
        service_type IN ('PRIMARY_LLM', 'EMBEDDING', 'ASR', 'TTS', 'FALLBACK_LLM', 'WEB_SEARCH')
    ),
    CONSTRAINT fk_system_api_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE SET NULL
);

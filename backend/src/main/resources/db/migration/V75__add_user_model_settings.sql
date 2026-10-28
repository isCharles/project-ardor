-- Per-user overrides for a model's visibility, thinking effort and context window.
-- Keyed by the provider/base URL/model fingerprint so a renamed catalog entry cannot
-- inherit another model's budget.
CREATE TABLE user_model_settings (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    fingerprint CHAR(64) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    thinking_effort VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    context_window_tokens BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, fingerprint),
    CONSTRAINT ck_user_model_settings_effort
        CHECK (thinking_effort IN ('AUTO', 'QUICK', 'STANDARD', 'DEEP')),
    CONSTRAINT ck_user_model_settings_window
        CHECK (context_window_tokens IS NULL
            OR (context_window_tokens >= 32768 AND context_window_tokens <= 10000000))
);

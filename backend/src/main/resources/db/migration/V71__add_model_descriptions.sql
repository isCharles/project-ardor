CREATE TABLE model_descriptions (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    model_key VARCHAR(200) NOT NULL,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('zh', 'en')),
    config_fingerprint CHAR(64) NOT NULL,
    description VARCHAR(160),
    failed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, model_key, locale),
    CONSTRAINT ck_model_descriptions_state CHECK ((description IS NULL) <> (failed_at IS NULL))
);

-- V88: Token resource packs. Plan tokens are consumed first; packs cover the
-- remainder after the monthly plan budget is exhausted. Packs are granted by
-- administrator-issued codes until a payment provider is integrated.

CREATE TABLE usage_token_packs (
    code VARCHAR(32) PRIMARY KEY,
    name_zh VARCHAR(40) NOT NULL,
    name_en VARCHAR(40) NOT NULL,
    token_amount BIGINT NOT NULL CHECK (token_amount > 0),
    price_cents INTEGER NOT NULL DEFAULT 0 CHECK (price_cents >= 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO usage_token_packs (code, name_zh, name_en, token_amount, price_cents, sort_order)
VALUES
    ('PACK_1M', '轻量资源包', 'Light pack', 1000000, 900, 0),
    ('PACK_5M', '标准资源包', 'Standard pack', 5000000, 3900, 1),
    ('PACK_20M', '大容量资源包', 'Large pack', 20000000, 12900, 2);

CREATE TABLE usage_token_pack_codes (
    id UUID PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    code_hint VARCHAR(20) NOT NULL,
    pack_code VARCHAR(32) NOT NULL REFERENCES usage_token_packs (code),
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    expires_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_usage_token_pack_codes_created ON usage_token_pack_codes (created_at DESC);

CREATE TABLE usage_token_pack_redemptions (
    id UUID PRIMARY KEY,
    code_id UUID NOT NULL REFERENCES usage_token_pack_codes (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pack_code VARCHAR(32) NOT NULL REFERENCES usage_token_packs (code),
    tokens BIGINT NOT NULL CHECK (tokens > 0),
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (code_id, user_id)
);
CREATE INDEX ix_usage_token_pack_redemptions_user
    ON usage_token_pack_redemptions (user_id, redeemed_at DESC);

ALTER TABLE usage_token_ledger ADD COLUMN pack_tokens BIGINT NOT NULL DEFAULT 0
    CONSTRAINT usage_token_ledger_pack_tokens_check CHECK (pack_tokens >= 0);
ALTER TABLE usage_token_ledger ADD CONSTRAINT usage_token_ledger_pack_within_total
    CHECK (pack_tokens <= total_tokens);
CREATE INDEX ix_usage_token_ledger_user_pack
    ON usage_token_ledger (user_id, created_at) WHERE pack_tokens > 0;

COMMENT ON TABLE usage_token_packs IS
    'Token resource packs; prices are informational until a payment provider exists.';
COMMENT ON TABLE usage_token_pack_codes IS
    'Admin-issued resource-pack codes. Only the hash and hint are stored; the plaintext code is shown once.';
COMMENT ON COLUMN usage_token_ledger.pack_tokens IS
    'Tokens paid from a resource pack after the monthly plan budget was exhausted.';

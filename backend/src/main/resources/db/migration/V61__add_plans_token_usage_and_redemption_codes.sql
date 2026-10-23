-- V61: Free/Plus/Pro plans, per-plan monthly token budgets, module limits and
-- administrator-issued redemption codes. Payment remains disabled until a
-- payment provider is added; codes are the only working upgrade path.

ALTER TABLE user_memberships DROP CONSTRAINT user_memberships_tier_check;
ALTER TABLE user_memberships DROP CONSTRAINT user_memberships_grant_source_check;
UPDATE user_memberships SET tier = 'PLUS' WHERE tier = 'MEMBER';
ALTER TABLE user_memberships ADD CONSTRAINT user_memberships_tier_check
    CHECK (tier IN ('FREE', 'PLUS', 'PRO'));
ALTER TABLE user_memberships ADD CONSTRAINT user_memberships_grant_source_check
    CHECK (grant_source IN ('GIFT', 'ADMIN', 'REDEEM', 'PAYMENT'));

ALTER TABLE usage_policies ADD COLUMN plus_monthly_limit INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usage_policies ADD COLUMN pro_monthly_limit INTEGER NOT NULL DEFAULT 0;
UPDATE usage_policies SET plus_monthly_limit = member_monthly_limit,
    pro_monthly_limit = member_monthly_limit * 4;
ALTER TABLE usage_policies ADD CONSTRAINT usage_policies_plus_monthly_limit_check
    CHECK (plus_monthly_limit >= 0);
ALTER TABLE usage_policies ADD CONSTRAINT usage_policies_pro_monthly_limit_check
    CHECK (pro_monthly_limit >= 0);

CREATE TABLE membership_plans (
    code VARCHAR(16) PRIMARY KEY CHECK (code IN ('FREE', 'PLUS', 'PRO')),
    name_zh VARCHAR(40) NOT NULL,
    name_en VARCHAR(40) NOT NULL,
    price_cents INTEGER NOT NULL DEFAULT 0 CHECK (price_cents >= 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    billing_interval VARCHAR(16) NOT NULL DEFAULT 'MONTH',
    token_monthly_limit BIGINT NOT NULL DEFAULT 0 CHECK (token_monthly_limit >= 0),
    payment_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO membership_plans (code, name_zh, name_en, price_cents, token_monthly_limit, payment_enabled, sort_order)
VALUES
    ('FREE', '免费版', 'Free', 0, 50000, FALSE, 0),
    ('PLUS', 'Plus', 'Plus', 3900, 2000000, FALSE, 1),
    ('PRO', 'Pro', 'Pro', 9900, 8000000, FALSE, 2);

CREATE TABLE usage_token_ledger (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    feature VARCHAR(40) NOT NULL,
    request_id UUID,
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens BIGINT NOT NULL CHECK (total_tokens >= 0),
    model VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_usage_token_ledger_request
    ON usage_token_ledger (user_id, feature, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX ix_usage_token_ledger_user_created
    ON usage_token_ledger (user_id, created_at);

CREATE TABLE redemption_codes (
    id UUID PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    code_hint VARCHAR(20) NOT NULL,
    plan_tier VARCHAR(16) NOT NULL CHECK (plan_tier IN ('PLUS', 'PRO')),
    duration_days INTEGER NOT NULL CHECK (duration_days > 0),
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    used_count INTEGER NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    expires_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_redemption_codes_created ON redemption_codes (created_at DESC);

CREATE TABLE redemption_redemptions (
    id UUID PRIMARY KEY,
    code_id UUID NOT NULL REFERENCES redemption_codes (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    redeemed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    UNIQUE (code_id, user_id)
);
CREATE INDEX ix_redemption_redemptions_user ON redemption_redemptions (user_id, redeemed_at DESC);

COMMENT ON COLUMN usage_policies.member_monthly_limit IS
    'Legacy column retained for compatibility; runtime uses plus_monthly_limit and pro_monthly_limit.';
COMMENT ON TABLE usage_token_ledger IS
    'Actual LLM token usage recorded after a run. request_id makes Agent retries idempotent.';
COMMENT ON TABLE redemption_codes IS
    'Admin-issued plan codes. The plaintext code is returned once; only the hash and hint are stored.';

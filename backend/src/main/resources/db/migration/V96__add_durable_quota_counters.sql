-- Business-period feature quotas move out of volatile Redis counters into PostgreSQL.
-- Redis keeps only the short burst windows, which are throttles rather than entitlements.
CREATE TABLE usage_quota_counters (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    feature VARCHAR(40) NOT NULL,
    period_kind VARCHAR(8) NOT NULL CHECK (period_kind IN ('MONTH', 'WEEK')),
    period_key VARCHAR(10) NOT NULL,
    used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, feature, period_kind, period_key)
);

-- Durable request marks: a transport retry must not be charged twice, even after a Redis flush.
CREATE TABLE usage_quota_requests (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    feature VARCHAR(40) NOT NULL,
    request_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, feature, request_id)
);
CREATE INDEX ix_usage_quota_requests_created ON usage_quota_requests (created_at);

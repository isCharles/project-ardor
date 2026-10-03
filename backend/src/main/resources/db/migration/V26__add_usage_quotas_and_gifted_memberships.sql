CREATE TABLE usage_policies (
    feature VARCHAR(40) PRIMARY KEY,
    free_monthly_limit INTEGER NOT NULL CHECK (free_monthly_limit >= 0),
    member_monthly_limit INTEGER NOT NULL CHECK (member_monthly_limit >= 0),
    user_minute_limit INTEGER NOT NULL CHECK (user_minute_limit > 0),
    global_minute_limit INTEGER NOT NULL CHECK (global_minute_limit > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO usage_policies (feature, free_monthly_limit, member_monthly_limit, user_minute_limit, global_minute_limit) VALUES
    ('AGENT_CHAT', 5, 500, 8, 30),
    ('RESUME_ANALYSIS', 1, 40, 3, 30),
    ('INTERVIEW_CREATE', 1, 40, 3, 30),
    ('INTERVIEW_EVALUATION', 1, 40, 3, 30),
    ('INTERVIEW_RECAP', 1, 40, 3, 30),
    ('INTERVIEW_REPLAY', 1, 120, 5, 60),
    ('LEARNING_PLAN', 1, 80, 5, 60),
    ('LEARNING_ATTEMPT', 2, 200, 8, 100),
    ('KNOWLEDGE_UPLOAD', 2, 100, 5, 60),
    ('KNOWLEDGE_SEARCH', 20, 1000, 20, 240),
    ('KNOWLEDGE_RESEARCH', 1, 100, 5, 60),
    ('CONNECTION_TEST', 5, 100, 10, 100),
    ('VOICE_ASR', 3, 300, 10, 120),
    ('VOICE_TTS', 3, 300, 10, 120);

CREATE TABLE user_memberships (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    tier VARCHAR(24) NOT NULL CHECK (tier IN ('FREE', 'MEMBER')),
    grant_source VARCHAR(24) NOT NULL CHECK (grant_source IN ('GIFT', 'ADMIN')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO user_memberships (user_id, tier, grant_source)
SELECT id, 'MEMBER', 'GIFT' FROM users;

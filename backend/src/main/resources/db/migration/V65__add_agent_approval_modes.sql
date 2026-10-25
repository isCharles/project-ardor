-- Missing preference means CUSTOM: retain all existing 0-3 grants unchanged.
CREATE TABLE agent_approval_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ASK', 'AUTO', 'FULL', 'CUSTOM')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

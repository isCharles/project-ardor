-- Agent permission levels are per user and per module. Level 0 keeps the old
-- behaviour (every write asks once); higher levels auto-approve reads, creates,
-- updates and finally external actions. Deletes never auto-approve: they stay
-- proposal cards that only an authenticated REST call can execute.
CREATE TABLE agent_permission_grants (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    module VARCHAR(40) NOT NULL,
    level SMALLINT NOT NULL DEFAULT 0 CHECK (level BETWEEN 0 AND 3),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, module)
);

-- Auto-approved writes leave a record; only a digest of the arguments is kept.
CREATE TABLE agent_tool_audit (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    run_id UUID,
    tool VARCHAR(80) NOT NULL,
    module VARCHAR(40) NOT NULL,
    capability VARCHAR(16) NOT NULL,
    arguments_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_agent_tool_audit_owner ON agent_tool_audit(user_id, created_at DESC);

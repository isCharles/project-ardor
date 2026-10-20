-- E2: durable links between user messages, agent runs, tool calls and proposals.
-- Additive only: old JSON projections (messages.tool_calls, messages.proposal_resolutions,
-- agent_tool_audit) keep their shape and continue to be written. Nullable links mean an
-- old client, an old worker or an unparseable historical row stays valid instead of being
-- guessed into a false relationship.

-- 1. Messages can carry the durable request/run identity. Both columns are nullable:
--    legacy synchronous chat and unparseable history remain unlinked.
ALTER TABLE messages ADD COLUMN request_id UUID;
ALTER TABLE messages ADD COLUMN run_id UUID;
ALTER TABLE messages ADD CONSTRAINT fk_messages_run_owner
    FOREIGN KEY (run_id, user_id) REFERENCES agent_runs (id, user_id)
    ON DELETE SET NULL (run_id);
CREATE INDEX ix_messages_owner_run ON messages (user_id, run_id);
CREATE INDEX ix_messages_owner_request ON messages (user_id, request_id);

-- 2. Backfill only links that can be proven, never a timestamp guess.
--    (a) A completed run already points at the assistant message it saved through
--        agent_runs.assistant_message_id (V22). If exactly one run owns that message,
--        both its request_id and run_id are that run.
WITH linked AS (
    SELECT r.assistant_message_id AS message_id,
           r.user_id,
           min(r.id::text)::uuid AS run_id,
           count(DISTINCT r.id) AS matches
    FROM agent_runs r
    WHERE r.assistant_message_id IS NOT NULL
    GROUP BY r.assistant_message_id, r.user_id
)
UPDATE messages m
SET request_id = linked.run_id,
    run_id = linked.run_id,
    updated_at = CURRENT_TIMESTAMP
FROM linked
WHERE m.id = linked.message_id
  AND m.user_id = linked.user_id
  AND m.run_id IS NULL
  AND linked.matches = 1;

--    (b) The user message of a saved exchange is the immediately preceding row of the
--        linked assistant message and its stored content is the run's own user message.
--        Ambiguous adjacency or different content stays NULL.
WITH ordered AS (
    SELECT id, user_id, conversation_id, role, content, created_at,
           lag(id) OVER w AS previous_id,
           lag(role) OVER w AS previous_role,
           lag(content) OVER w AS previous_content
    FROM messages
    WINDOW w AS (PARTITION BY user_id, conversation_id ORDER BY created_at, id)
), candidates AS (
    SELECT ordered.previous_id AS message_id,
           ordered.user_id,
           runs.id AS run_id
    FROM ordered
    JOIN messages ON messages.id = ordered.id
    JOIN agent_runs runs ON runs.id = messages.run_id AND runs.user_id = messages.user_id
    WHERE messages.role = 'ASSISTANT'
      AND messages.run_id IS NOT NULL
      AND ordered.previous_role = 'USER'
      AND ordered.previous_content = runs.message
)
UPDATE messages target
SET request_id = candidates.run_id,
    run_id = candidates.run_id,
    updated_at = CURRENT_TIMESTAMP
FROM candidates
WHERE target.id = candidates.message_id
  AND target.user_id = candidates.user_id
  AND target.run_id IS NULL;

COMMENT ON COLUMN messages.request_id IS
    'Client request identity; equals agent_runs.id for a durable run. NULL for legacy chat and unparseable history.';
COMMENT ON COLUMN messages.run_id IS
    'Durable agent run that saved this message; NULL for legacy chat and unparseable history.';

-- 3. Structured per-invocation tool call facts. Digests and bounded references only:
--    full arguments, results, prompts and secrets are never copied here.
CREATE TABLE agent_tool_calls (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    run_id UUID NOT NULL,
    message_id UUID,
    tool_call_id VARCHAR(200),
    tool_name VARCHAR(80) NOT NULL,
    module VARCHAR(40),
    capability VARCHAR(16),
    approval_source VARCHAR(20) NOT NULL,
    arguments_digest CHAR(64) NOT NULL,
    arguments_size INTEGER,
    result_digest CHAR(64),
    result_size INTEGER,
    result_ref VARCHAR(200),
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(80),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_tool_calls_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_agent_tool_calls_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tool_calls_conversation_owner FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tool_calls_run_owner FOREIGN KEY (run_id, user_id)
        REFERENCES agent_runs (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tool_calls_message_owner FOREIGN KEY (message_id, user_id)
        REFERENCES messages (id, user_id) ON DELETE SET NULL (message_id),
    CONSTRAINT ck_agent_tool_calls_source CHECK
        (approval_source IN ('AUTO', 'CONFIRMED', 'DELETE_PROPOSAL', 'PROPOSAL')),
    CONSTRAINT ck_agent_tool_calls_status CHECK
        (status IN ('STARTED', 'COMPLETED', 'FAILED', 'NOT_EXECUTED', 'PROPOSED', 'UNKNOWN'))
);
CREATE INDEX ix_agent_tool_calls_owner_run
    ON agent_tool_calls (user_id, run_id, started_at);
CREATE INDEX ix_agent_tool_calls_open
    ON agent_tool_calls (status) WHERE status = 'STARTED';
COMMENT ON COLUMN agent_tool_calls.result_ref IS
    'Opaque correlation reference (for example a created job/resource UUID). Never an authorization or completion proof.';
COMMENT ON COLUMN agent_tool_calls.status IS
    'STARTED, COMPLETED, FAILED, NOT_EXECUTED, PROPOSED (card only), UNKNOWN (started, outcome cannot be confirmed).';

-- 4. Proposals get a stable identity and a lifecycle instead of being re-derived from trace JSON.
CREATE TABLE agent_proposals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    run_id UUID NOT NULL,
    message_id UUID,
    kind VARCHAR(40) NOT NULL,
    target_id UUID,
    target_ids JSONB,
    legacy_key TEXT NOT NULL,
    label VARCHAR(240),
    detail TEXT,
    endpoint VARCHAR(240),
    learning_request_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    resolution VARCHAR(20),
    resolution_source VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_proposals_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_agent_proposals_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_proposals_conversation_owner FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_proposals_run_owner FOREIGN KEY (run_id, user_id)
        REFERENCES agent_runs (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_proposals_message_owner FOREIGN KEY (message_id, user_id)
        REFERENCES messages (id, user_id) ON DELETE SET NULL (message_id),
    CONSTRAINT ck_agent_proposals_status CHECK
        (status IN ('PROPOSED', 'CONFIRMED', 'DISMISSED', 'EXPIRED', 'CANCELLED'))
);
CREATE INDEX ix_agent_proposals_owner_run
    ON agent_proposals (user_id, run_id, created_at);
CREATE INDEX ix_agent_proposals_owner_message
    ON agent_proposals (user_id, message_id);
COMMENT ON COLUMN agent_proposals.legacy_key IS
    'Existing card key (learning request ID or kind:endpoint) so old clients keep resolving the same proposal.';
COMMENT ON COLUMN agent_proposals.status IS
    'Presentation decision only. CONFIRMED is a user decision report, not proof that the business mutation succeeded.';

-- 5. The legacy audit table keeps its rows and gains the approval source. Existing rows
--    were auto approvals, so the default preserves their historical meaning; old writers
--    that do not name the column also keep producing AUTO rows.
ALTER TABLE agent_tool_audit ADD COLUMN approval_source VARCHAR(20) NOT NULL DEFAULT 'AUTO';
ALTER TABLE agent_tool_audit ADD CONSTRAINT ck_agent_tool_audit_source CHECK
    (approval_source IN ('AUTO', 'CONFIRMED', 'DELETE_PROPOSAL', 'PROPOSAL'));
CREATE INDEX ix_agent_tool_audit_owner_run
    ON agent_tool_audit (user_id, run_id, created_at DESC);

-- Rollback (manual, only during this migration's own deployment window; the columns added
-- above are additive and no existing column was rewritten):
--   DROP TABLE IF EXISTS agent_proposals;
--   DROP TABLE IF EXISTS agent_tool_calls;
--   DROP INDEX IF EXISTS ix_agent_tool_audit_owner_run;
--   ALTER TABLE agent_tool_audit DROP CONSTRAINT IF EXISTS ck_agent_tool_audit_source;
--   ALTER TABLE agent_tool_audit DROP COLUMN IF EXISTS approval_source;
--   DROP INDEX IF EXISTS ix_messages_owner_request;
--   DROP INDEX IF EXISTS ix_messages_owner_run;
--   ALTER TABLE messages DROP CONSTRAINT IF EXISTS fk_messages_run_owner;
--   ALTER TABLE messages DROP COLUMN IF EXISTS run_id;
--   ALTER TABLE messages DROP COLUMN IF EXISTS request_id;

-- Memory items become facts with provenance and validity instead of verbatim quotes.
-- Existing rows are user-supplied facts at full confidence and remain valid.
ALTER TABLE user_agent_memory_items
    ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT 'fact',
    ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0,
    ADD COLUMN importance SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'USER_EXPLICIT',
    ADD COLUMN source_run_id UUID,
    ADD COLUMN valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN superseded_by UUID;

ALTER TABLE user_agent_memory_items
    ADD CONSTRAINT ck_agent_memory_item_kind
        CHECK (kind IN ('profile', 'preference', 'goal', 'constraint', 'state', 'fact')),
    ADD CONSTRAINT ck_agent_memory_item_source
        CHECK (source_kind IN ('USER_EXPLICIT', 'USER_STATED', 'INFERRED'));

CREATE INDEX ix_agent_memory_item_valid ON user_agent_memory_items(user_id, valid_until);

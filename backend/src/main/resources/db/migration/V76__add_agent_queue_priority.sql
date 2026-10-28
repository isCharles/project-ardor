ALTER TABLE agent_message_queue ADD COLUMN priority SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE agent_message_queue ADD CONSTRAINT ck_agent_queue_priority
    CHECK (priority IN (0, 1));

-- Claim order also ages priority by wait time, so this index only narrows the queued set.
CREATE INDEX ix_agent_queue_queued_priority ON agent_message_queue(priority DESC, sequence)
    WHERE status = 'QUEUED';

-- Preserve the authenticated attachment snapshot even when no exchange completes.
ALTER TABLE agent_runs ADD COLUMN context_references JSONB NOT NULL DEFAULT '[]'::jsonb;

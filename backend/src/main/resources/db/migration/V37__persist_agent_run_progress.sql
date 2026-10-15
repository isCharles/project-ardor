ALTER TABLE agent_runs ADD COLUMN progress_snapshot JSONB;
COMMENT ON COLUMN agent_runs.progress_snapshot IS 'Bounded public model output and execution steps; never an approval or replay instruction';

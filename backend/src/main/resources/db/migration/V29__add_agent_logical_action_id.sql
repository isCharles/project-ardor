-- A replacement run can reuse the original tool-side effect key after an interrupted run.
ALTER TABLE agent_runs ADD COLUMN logical_action_id UUID;

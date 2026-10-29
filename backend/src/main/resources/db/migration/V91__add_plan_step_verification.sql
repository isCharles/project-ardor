-- Additive verification contract for plan steps. Existing plans keep NULL and are never
-- retroactively marked verified; new plans must declare a predicate over a real tool result.
ALTER TABLE agent_plan_steps
    ADD COLUMN verify_tool VARCHAR(80),
    ADD COLUMN verify_path VARCHAR(200),
    ADD COLUMN verify_op VARCHAR(24),
    ADD COLUMN verify_value VARCHAR(200);

COMMENT ON COLUMN agent_plan_steps.verify_tool IS
    'Tool whose actual result is checked; must be one of the step tools.';
COMMENT ON COLUMN agent_plan_steps.verify_path IS
    'JSON path into the tool result: $, $.field, $[0], $.items[0].field.';
COMMENT ON COLUMN agent_plan_steps.verify_op IS
    'MIN_ITEMS/MAX_ITEMS/AT_LEAST/EQUALS/CONTAINS/HAS_ID/SUCCESS.';
COMMENT ON COLUMN agent_plan_steps.verify_value IS
    'Expected scalar for the operator; NULL for HAS_ID/SUCCESS.';

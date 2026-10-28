-- Durable plans for cross-module, multi-goal agent tasks: what was promised, which steps were
-- verified against real business results, and how many times the plan had to be revised.
-- A plan is an execution record attached to a run, not a second runtime: the graph view reads the
-- same rows. Rollback: DROP TABLE agent_plan_steps; DROP TABLE agent_plans;
CREATE TABLE agent_plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    request_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    goal VARCHAR(600) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROPOSED',
    revision INTEGER NOT NULL DEFAULT 0,
    model_call_ceiling INTEGER NOT NULL,
    model_calls_used INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_plans_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_agent_plans_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_plans_run FOREIGN KEY (request_id, user_id)
        REFERENCES agent_runs (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_plans_conversation FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_plans_status CHECK (status IN
        ('PROPOSED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT ck_agent_plans_revision CHECK (revision BETWEEN 0 AND 2),
    CONSTRAINT ck_agent_plans_ceiling CHECK (model_call_ceiling > 0 AND model_calls_used >= 0)
);

CREATE INDEX ix_agent_plans_owner_conversation ON agent_plans (user_id, conversation_id, created_at DESC);

CREATE TABLE agent_plan_steps (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    user_id UUID NOT NULL,
    step_index INTEGER NOT NULL,
    intent VARCHAR(600) NOT NULL,
    done_when VARCHAR(600) NOT NULL,
    tools VARCHAR(400) NOT NULL,
    effect VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    logical_action_id UUID,
    evidence VARCHAR(900),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_plan_steps_position UNIQUE (plan_id, step_index),
    CONSTRAINT uq_agent_plan_steps_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_agent_plan_steps_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES agent_plans (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_plan_steps_effect CHECK (effect IN ('READ', 'WRITE')),
    CONSTRAINT ck_agent_plan_steps_status CHECK (status IN
        ('PENDING', 'IN_PROGRESS', 'VERIFIED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_agent_plan_steps_verified CHECK (status <> 'VERIFIED' OR verified_at IS NOT NULL),
    CONSTRAINT ck_agent_plan_steps_index CHECK (step_index BETWEEN 0 AND 11)
);

CREATE INDEX ix_agent_plan_steps_plan ON agent_plan_steps (plan_id, step_index);

-- Learning attempt history plus lesson/exercise content versions.
--
-- Additive and rollback-safe: the lesson/exercises JSONB and the truncated
-- learning_plans.last_evaluation.attempts projection stay untouched for old clients.
-- learning_attempts is the new source of truth for every graded submission; nothing in
-- this migration rewrites historical columns, resource UUIDs or model output.
--
-- Rollback: stop writing/reading learning_attempts and ignore content_version/
-- exercises_version. Do not drop the table and never edit an executed migration.
-- Deleting a learning_plan now also deletes its attempt rows through the ownership
-- composite foreign key (ON DELETE CASCADE); that is the only deletion behavior change.

ALTER TABLE learning_plans ADD COLUMN content_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE learning_plans ADD COLUMN exercises_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE learning_plans ADD CONSTRAINT ck_learning_content_version CHECK (content_version >= 1);
ALTER TABLE learning_plans ADD CONSTRAINT ck_learning_exercises_version CHECK (exercises_version >= 1);

CREATE TABLE learning_attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    request_id UUID,
    exercise_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb,
    answers JSONB NOT NULL DEFAULT '[]'::jsonb,
    score INTEGER,
    evaluation JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_name VARCHAR(120),
    prompt_version VARCHAR(40),
    legacy BOOLEAN NOT NULL DEFAULT FALSE,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_learning_attempts_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_learning_attempts_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_learning_attempts_snapshot CHECK (jsonb_typeof(exercise_snapshot) = 'array'),
    CONSTRAINT ck_learning_attempts_answers CHECK (jsonb_typeof(answers) = 'array'),
    CONSTRAINT ck_learning_attempts_evaluation CHECK (jsonb_typeof(evaluation) = 'object'),
    CONSTRAINT uq_learning_attempts_plan_no UNIQUE (user_id, plan_id, attempt_no),
    CONSTRAINT fk_learning_attempts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_attempts_plan_owner FOREIGN KEY (plan_id, user_id)
        REFERENCES learning_plans (id, user_id) ON DELETE CASCADE
);

-- Request IDs are optional: synchronous submissions do not carry one, async jobs do.
CREATE UNIQUE INDEX uq_learning_attempts_user_request ON learning_attempts (user_id, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX ix_learning_attempts_plan_no ON learning_attempts (user_id, plan_id, attempt_no DESC);
CREATE INDEX ix_learning_attempts_user_time ON learning_attempts (user_id, submitted_at DESC);

-- Backfill the legacy projection. JSONB array order is the only ordering evidence, so
-- attempt_no is anchored to learning_plans.attempt_count: the projection retains the
-- newest 20 attempts, i.e. attempt_count - retained + 1 .. attempt_count. Inconsistent
-- rows (retained > attempt_count) start at 1 instead of inventing negative numbers.
--
-- Everything the old projection never stored (per-attempt rubric, strengths/gaps/
-- nextFocus, grading model and prompt version) stays NULL instead of being fabricated;
-- those rows are marked legacy = TRUE. Snapshot/answer/score/feedback, the request ID
-- and submittedAt are reconstructed only when the JSONB value actually holds them.
WITH projected AS (
    SELECT p.id AS plan_id,
           p.user_id,
           p.attempt_count,
           p.created_at AS plan_created_at,
           entry.value AS attempt,
           entry.ordinality AS position,
           count(*) OVER (PARTITION BY p.id) AS projected_count
    FROM learning_plans p
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
             THEN p.last_evaluation -> 'attempts'
             ELSE '[]'::jsonb END
    ) WITH ORDINALITY AS entry(value, ordinality)
),
numbered AS (
    SELECT projected.*,
           GREATEST(1, projected.attempt_count - projected.projected_count + 1)
               + projected.position - 1 AS attempt_no,
           row_number() OVER (
               PARTITION BY projected.user_id, projected.attempt -> 'requestId'
               ORDER BY projected.plan_created_at, projected.plan_id, projected.position
           ) AS request_rank
    FROM projected
),
items AS (
    SELECT numbered.*,
           CASE WHEN jsonb_typeof(numbered.attempt -> 'items') = 'array'
                THEN numbered.attempt -> 'items'
                ELSE '[]'::jsonb END AS attempt_items
    FROM numbered
)
INSERT INTO learning_attempts
    (id, user_id, plan_id, attempt_no, request_id, exercise_snapshot, answers,
     score, evaluation, model_name, prompt_version, legacy, submitted_at, created_at)
SELECT
    gen_random_uuid(),
    items.user_id,
    items.plan_id,
    items.attempt_no,
    -- The legacy projection may repeat a request ID; keep it only on the first row so the
    -- (user_id, request_id) unique contract can never abort the upgrade on dirty history.
    CASE WHEN items.request_rank = 1
              AND jsonb_typeof(items.attempt -> 'requestId') = 'string'
              AND pg_input_is_valid(items.attempt ->> 'requestId', 'uuid')
         THEN (items.attempt ->> 'requestId')::uuid END,
    COALESCE((
        SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                   'question', exercise.value -> 'question',
                   'type', COALESCE(exercise.value -> 'type', '"SHORT_ANSWER"'::jsonb),
                   'options', CASE WHEN jsonb_typeof(exercise.value -> 'options') = 'array'
                                   THEN exercise.value -> 'options'
                                   ELSE '[]'::jsonb END))
               ORDER BY exercise.ordinality)
        FROM jsonb_array_elements(items.attempt_items) WITH ORDINALITY AS exercise(value, ordinality)
    ), '[]'::jsonb),
    COALESCE((
        SELECT jsonb_agg(COALESCE(exercise.value ->> 'answer', '') ORDER BY exercise.ordinality)
        FROM jsonb_array_elements(items.attempt_items) WITH ORDINALITY AS exercise(value, ordinality)
    ), '[]'::jsonb),
    CASE WHEN jsonb_typeof(items.attempt -> 'score') = 'number'
              AND pg_input_is_valid(items.attempt ->> 'score', 'integer')
              AND (items.attempt ->> 'score')::integer BETWEEN 0 AND 100
         THEN (items.attempt ->> 'score')::integer END,
    jsonb_strip_nulls(jsonb_build_object(
        'feedback', items.attempt -> 'feedback',
        'questionFeedback', CASE WHEN jsonb_typeof(items.attempt -> 'questionFeedback') = 'array'
                                 THEN items.attempt -> 'questionFeedback'
                                 ELSE '[]'::jsonb END,
        'score', items.attempt -> 'score')),
    NULL,
    NULL,
    TRUE,
    CASE WHEN jsonb_typeof(items.attempt -> 'submittedAt') = 'string'
              AND pg_input_is_valid(items.attempt ->> 'submittedAt', 'timestamptz')
         THEN (items.attempt ->> 'submittedAt')::timestamptz END,
    CURRENT_TIMESTAMP
FROM items;

COMMENT ON TABLE learning_attempts IS
    'Immutable graded submission per learning plan round; legacy=TRUE rows were backfilled from last_evaluation.attempts and are not fabricated.';
COMMENT ON COLUMN learning_plans.content_version IS
    'Version of the lesson content; incremented when lesson text is regenerated.';
COMMENT ON COLUMN learning_plans.exercises_version IS
    'Version of the exercise set; incremented when the set is replaced so stale answers cannot attach to new questions.';

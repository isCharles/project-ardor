\set ON_ERROR_STOP on
DO $$
DECLARE
    backfilled integer;
    non_legacy integer;
    retained integer;
    first_attempt_no integer;
    last_attempt_no integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '52' AND success) THEN
        RAISE EXCEPTION 'V52 was not applied by Flyway';
    END IF;

    -- Content/exercise versions are additive, defaulted to 1, and historical plans keep
    -- their lesson/exercises/last_evaluation/progress untouched.
    IF (SELECT count(*) FROM learning_plans
        WHERE user_id = '60000000-0000-0000-0000-000000000001'
          AND content_version = 1 AND exercises_version = 1) <> 4 THEN
        RAISE EXCEPTION 'Historical plans did not receive default content/exercise versions';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans
        WHERE id = '61000000-0000-0000-0000-000000000000'
          AND origin_kind = 'LEGACY_RECAP'
          AND origin_resource_id = '62000000-0000-0000-0000-000000000001'
          AND lesson = '{"summary":"Zero history lesson"}'::jsonb
          AND last_evaluation = '{}'::jsonb
          AND attempt_count = 0) THEN
        RAISE EXCEPTION 'LEGACY_RECAP origin or untouched historical plan changed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans
        WHERE id = '61000000-0000-0000-0000-000000000002'
          AND attempt_count = 2 AND last_score = 85
          AND jsonb_array_length(last_evaluation -> 'attempts') = 2) THEN
        RAISE EXCEPTION 'Legacy attempt projection was rewritten';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans
        WHERE id = '61000000-0000-0000-0000-000000000025'
          AND attempt_count = 25
          AND jsonb_array_length(last_evaluation -> 'attempts') = 20) THEN
        RAISE EXCEPTION 'Truncated legacy projection changed';
    END IF;

    -- 0 rounds: no invented attempt rows.
    IF (SELECT count(*) FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000000') <> 0 THEN
        RAISE EXCEPTION 'Zero-history plan received fabricated attempts';
    END IF;

    -- 2 recoverable rounds: ordered, legacy, snapshot/answer/score/feedback restored,
    -- duplicate request ID kept only on the first row, no invented model/prompt/rubric.
    SELECT count(*) INTO retained FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000002';
    IF retained <> 2 THEN RAISE EXCEPTION 'Two-history plan backfilled % rows instead of 2', retained; END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000002' AND attempt_no = 1
          AND legacy AND request_id = '63000000-0000-4000-8000-000000000001'
          AND score = 61 AND model_name IS NULL AND prompt_version IS NULL
          AND submitted_at = '2026-10-06T01:00:00Z'::timestamptz
          AND answers = '["Legacy A1","Legacy A2"]'::jsonb
          AND exercise_snapshot = '[{"question":"Legacy Q1","type":"SHORT_ANSWER","options":[]},
                                    {"question":"Legacy Q2","type":"SCENARIO","options":[]}]'::jsonb
          AND evaluation = '{"score":61,"feedback":"Legacy feedback 1",
                              "questionFeedback":[{"question":"Legacy Q1","feedback":"Legacy per-question"}]}'::jsonb) THEN
        RAISE EXCEPTION 'First recoverable legacy attempt was not reconstructed faithfully';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000002' AND attempt_no = 2
          AND legacy AND request_id IS NULL
          AND score = 85 AND submitted_at = '2026-10-06T02:00:00Z'::timestamptz
          AND answers = '["Legacy A3"]'::jsonb) THEN
        RAISE EXCEPTION 'Duplicate historical request ID was not deduplicated';
    END IF;

    -- Inconsistent projection: 3 rounds but attempt_count = 1 were backfilled as attempt_no
    -- 1..3 without rewriting attempt_count. The runtime therefore allocates the next attempt
    -- from max(stored attempt_no, attempt_count) + 1 so the unique key cannot collide.
    SELECT count(*), min(attempt_no), max(attempt_no) INTO retained, first_attempt_no, last_attempt_no
        FROM learning_attempts WHERE plan_id = '61000000-0000-0000-0000-000000000003';
    IF retained <> 3 OR first_attempt_no <> 1 OR last_attempt_no <> 3 THEN
        RAISE EXCEPTION 'Inconsistent projection was not backfilled as stored history (rows=%, first=%, last=%)',
            retained, first_attempt_no, last_attempt_no;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000003' AND attempt_no = 3
          AND legacy AND score = 50 AND answers = '["Adversarial A3"]'::jsonb) THEN
        RAISE EXCEPTION 'Inconsistent projection history was changed or invented';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans
        WHERE id = '61000000-0000-0000-0000-000000000003'
          AND attempt_count = 1
          AND jsonb_array_length(last_evaluation -> 'attempts') = 3) THEN
        RAISE EXCEPTION 'Inconsistent historical attempt_count or projection was rewritten';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans p
        WHERE p.id = '61000000-0000-0000-0000-000000000003'
          AND (SELECT GREATEST(COALESCE(max(a.attempt_no), 0), p.attempt_count) + 1
               FROM learning_attempts a WHERE a.plan_id = p.id AND a.user_id = p.user_id) = 4) THEN
        RAISE EXCEPTION 'Next attempt number must continue after the stored maximum (expected 4)';
    END IF;

    -- Truncated 25-round history: the retained newest 20 are numbered 6..25, not 1..20.
    SELECT count(*), min(attempt_no), max(attempt_no) INTO retained, first_attempt_no, last_attempt_no
        FROM learning_attempts WHERE plan_id = '61000000-0000-0000-0000-000000000025';
    IF retained <> 20 OR first_attempt_no <> 6 OR last_attempt_no <> 25 THEN
        RAISE EXCEPTION 'Retained history was not anchored to attempt_count (rows=%, first=%, last=%)',
            retained, first_attempt_no, last_attempt_no;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_attempts
        WHERE plan_id = '61000000-0000-0000-0000-000000000025' AND attempt_no = 25
          AND request_id = '63000000-0000-4000-8000-000000000099'
          AND answers = '["Retained answer 25"]'::jsonb
          AND exercise_snapshot = '[{"question":"Retained question 25","type":"SHORT_ANSWER","options":[]}]'::jsonb) THEN
        RAISE EXCEPTION 'Newest retained attempt was not reconstructed';
    END IF;

    -- No fabricated grading metadata or rubric and no silently non-legacy backfill row.
    SELECT count(*), count(*) FILTER (WHERE NOT legacy) INTO backfilled, non_legacy FROM learning_attempts;
    IF backfilled <> 25 THEN RAISE EXCEPTION 'Expected 25 backfilled attempts, found %', backfilled; END IF;
    IF non_legacy <> 0 THEN RAISE EXCEPTION 'Backfilled history must stay legacy'; END IF;
    IF EXISTS (SELECT 1 FROM learning_attempts
        WHERE model_name IS NOT NULL OR prompt_version IS NOT NULL) THEN
        RAISE EXCEPTION 'Backfill invented evaluation model or prompt version';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_attempts a, LATERAL jsonb_array_elements(a.exercise_snapshot) item
               WHERE item ? 'rubric' OR item ? 'correctOption') THEN
        RAISE EXCEPTION 'Backfill invented rubric or choice answer';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_attempts WHERE score IS NOT NULL AND (score < 0 OR score > 100)) THEN
        RAISE EXCEPTION 'Backfilled a score outside the contract';
    END IF;

    -- Composite ownership: a foreign user cannot own an attempt on somebody else's plan.
    BEGIN
        INSERT INTO learning_attempts(id,user_id,plan_id,attempt_no,exercise_snapshot,answers,evaluation,legacy)
        VALUES (gen_random_uuid(),'60000000-0000-0000-0000-000000000002',
            '61000000-0000-0000-0000-000000000025',99,'[]'::jsonb,'[]'::jsonb,'{}'::jsonb,TRUE);
        RAISE EXCEPTION 'Composite foreign key accepted a foreign plan owner';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;

    -- Partial unique request index: a request ID already used by this user cannot repeat.
    BEGIN
        INSERT INTO learning_attempts(id,user_id,plan_id,attempt_no,request_id,exercise_snapshot,answers,evaluation,legacy)
        VALUES (gen_random_uuid(),'60000000-0000-0000-0000-000000000001',
            '61000000-0000-0000-0000-000000000025',26,'63000000-0000-4000-8000-000000000099',
            '[]'::jsonb,'[]'::jsonb,'{}'::jsonb,TRUE);
        RAISE EXCEPTION 'Duplicate attempt request ID was accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    -- Attempt history is owned by the plan and disappears with it (documented deletion change).
    DELETE FROM learning_plans WHERE id = '61000000-0000-0000-0000-000000000002';
    IF EXISTS (SELECT 1 FROM learning_attempts
               WHERE plan_id = '61000000-0000-0000-0000-000000000002') THEN
        RAISE EXCEPTION 'Deleting a learning plan left its attempts behind';
    END IF;

    RAISE NOTICE 'V52 anomaly handling verified: inconsistent projection attempt_no 1..3, next allocation 4';
    RAISE NOTICE 'V52 backfill verified: attempts=%, legacy=%, retained_plan_rounds=% (attempt_no %..%)',
        backfilled, backfilled, retained, first_attempt_no, last_attempt_no;
END $$;

\set ON_ERROR_STOP on
-- Read-only anomaly report executed by the V51 -> latest upgrade probe after V52.
-- It runs the same queries documented in docs/LEARNING_ATTEMPT_HISTORY.md and asserts the
-- synthetic anomaly rows are detected. No data is modified.
--
-- Report 1: legacy projection held more rounds than attempt_count. V52 backfills those rows
--           as attempt_no 1..retained; runtime allocation must continue after the stored max.
SELECT p.id AS plan_id, p.user_id, p.attempt_count,
       jsonb_array_length(p.last_evaluation -> 'attempts') AS projected_rows
FROM learning_plans p
WHERE jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
  AND jsonb_array_length(p.last_evaluation -> 'attempts') > p.attempt_count
ORDER BY p.id;

-- Report 2: attempts unrecoverable because the old projection only kept the newest 20 rounds.
SELECT p.id AS plan_id, p.attempt_count,
       jsonb_array_length(p.last_evaluation -> 'attempts') AS retained_rows,
       p.attempt_count - jsonb_array_length(p.last_evaluation -> 'attempts') AS unrecoverable_rows
FROM learning_plans p
WHERE jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
  AND p.attempt_count > jsonb_array_length(p.last_evaluation -> 'attempts')
ORDER BY unrecoverable_rows DESC, p.id;

DO $$
DECLARE
    projection_anomalies integer;
    truncated_plans integer;
BEGIN
    SELECT count(*) INTO projection_anomalies
    FROM learning_plans p
    WHERE jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
      AND jsonb_array_length(p.last_evaluation -> 'attempts') > p.attempt_count;
    IF projection_anomalies <> 1 THEN
        RAISE EXCEPTION 'Anomaly report expected exactly one projection>attempt_count plan, found %',
            projection_anomalies;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans p
        WHERE p.id = '61000000-0000-0000-0000-000000000003'
          AND p.attempt_count = 1
          AND jsonb_array_length(p.last_evaluation -> 'attempts') = 3
          AND NOT EXISTS (SELECT 1 FROM learning_attempts a
                          WHERE a.plan_id = p.id AND a.user_id = p.user_id AND a.attempt_no > 3)) THEN
        RAISE EXCEPTION 'Anomaly report missed the adversarial projection or invented numbering';
    END IF;

    SELECT count(*) INTO truncated_plans
    FROM learning_plans p
    WHERE jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
      AND p.attempt_count > jsonb_array_length(p.last_evaluation -> 'attempts');
    IF truncated_plans <> 1 THEN
        RAISE EXCEPTION 'Anomaly report expected exactly one truncation plan, found %', truncated_plans;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_plans p
        WHERE p.id = '61000000-0000-0000-0000-000000000025'
          AND p.attempt_count = 25
          AND jsonb_array_length(p.last_evaluation -> 'attempts') = 20
          AND p.attempt_count - jsonb_array_length(p.last_evaluation -> 'attempts') = 5) THEN
        RAISE EXCEPTION 'Anomaly report missed the five unrecoverable truncated rounds';
    END IF;

    RAISE NOTICE 'V52 anomaly report verified: projection>attempt_count=%, truncated_plans=% (unrecoverable=5)',
        projection_anomalies, truncated_plans;
END $$;

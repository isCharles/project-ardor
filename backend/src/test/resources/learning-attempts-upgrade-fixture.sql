-- Disposable V51 database only. All identities, text and model output are synthetic.
-- Covers the backfill contract: no history, 2 recoverable rounds (including a duplicated
-- request ID left by retried writes), a projection truncated to 20 out of 25 rounds, and a
-- LEGACY_RECAP origin that must not be reinterpreted by V52.
INSERT INTO users(id,email,password_hash) VALUES
 ('60000000-0000-0000-0000-000000000001','learning-attempt-upgrade@ardor.invalid','not-a-login-hash'),
 ('60000000-0000-0000-0000-000000000002','learning-attempt-foreign@ardor.invalid','not-a-login-hash');

-- 0 rounds, unresolved legacy origin must stay exactly as V51 left it.
INSERT INTO learning_plans(id,user_id,concept,reason,source_type,source_id,origin_kind,origin_resource_id,status,lesson,
    exercises,last_evaluation,attempt_count,last_score,scheduled_at)
VALUES ('61000000-0000-0000-0000-000000000000','60000000-0000-0000-0000-000000000001',
    'Zero history','Synthetic reason','RECAP','62000000-0000-0000-0000-000000000001','LEGACY_RECAP',
    '62000000-0000-0000-0000-000000000001','SCHEDULED',
    '{"summary":"Zero history lesson"}'::jsonb,
    '[{"question":"Zero exercise","rubric":["Synthetic rubric"]}]'::jsonb,
    '{}'::jsonb,0,NULL,'2099-01-01T09:00:00Z');

-- 2 rounds in the legacy projection. The second round repeats the first request ID: that
-- dirty retry must not abort the upgrade and only the first row may keep the unique key.
INSERT INTO learning_plans(id,user_id,concept,reason,source_type,source_id,origin_kind,status,lesson,exercises,
    last_evaluation,attempt_count,last_score,scheduled_at)
VALUES ('61000000-0000-0000-0000-000000000002','60000000-0000-0000-0000-000000000001',
    'Two history','Synthetic reason','MANUAL',NULL,'MANUAL','NEEDS_REVIEW',
    '{"summary":"Two history lesson"}'::jsonb,
    '[{"question":"Two exercise","rubric":["Synthetic rubric"]}]'::jsonb,
    '{"attempts":[
      {"items":[{"question":"Legacy Q1","type":"SHORT_ANSWER","answer":"Legacy A1"},
                {"question":"Legacy Q2","type":"SCENARIO","answer":"Legacy A2"}],
       "score":61,"feedback":"Legacy feedback 1",
       "questionFeedback":[{"question":"Legacy Q1","feedback":"Legacy per-question"}],
       "requestId":"63000000-0000-4000-8000-000000000001","submittedAt":"2026-10-06T01:00:00Z"},
      {"items":[{"question":"Legacy Q3","type":"SHORT_ANSWER","answer":"Legacy A3"}],
       "score":85,"feedback":"Legacy feedback 2","questionFeedback":[],
       "requestId":"63000000-0000-4000-8000-000000000001","submittedAt":"2026-10-06T02:00:00Z"}]}'::jsonb,
    2,85,'2099-01-02T09:00:00Z');

-- 25 graded rounds, but the compatibility projection only kept the newest 20 (rounds 6..25).
INSERT INTO learning_plans(id,user_id,concept,reason,source_type,source_id,origin_kind,status,lesson,exercises,
    last_evaluation,attempt_count,last_score,scheduled_at)
SELECT '61000000-0000-0000-0000-000000000025','60000000-0000-0000-0000-000000000001',
    'Twenty-five history','Synthetic reason','MANUAL',NULL,'MANUAL','NEEDS_REVIEW',
    '{"summary":"Twenty-five history lesson"}'::jsonb,
    '[{"question":"History exercise","rubric":["Synthetic rubric"]}]'::jsonb,
    jsonb_build_object('attempts', (
        SELECT jsonb_agg(jsonb_build_object(
            'items', jsonb_build_array(jsonb_build_object(
                'question','Retained question '||retained_no,
                'type','SHORT_ANSWER',
                'answer','Retained answer '||retained_no)),
            'score', 50,
            'feedback','Retained feedback '||retained_no,
            'questionFeedback','[]'::jsonb,
            'requestId', CASE WHEN retained_no = 25
                THEN to_jsonb('63000000-0000-4000-8000-000000000099'::text) END,
            'submittedAt','2026-10-07T01:00:00Z')
            ORDER BY retained_no)
        FROM generate_series(6,25) AS retained_no)),
    25,50,'2099-01-03T09:00:00Z';

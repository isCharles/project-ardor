\set ON_ERROR_STOP on
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='51' AND success) THEN RAISE EXCEPTION 'V51 missing'; END IF;
 IF NOT EXISTS(SELECT 1 FROM learning_plans WHERE id='80000000-0000-0000-0000-000000000001'
   AND origin_kind='INTERVIEW_RECORD' AND origin_resource_id=source_id AND origin_record_id=source_id) THEN RAISE EXCEPTION 'Record origin not resolved'; END IF;
 IF NOT EXISTS(SELECT 1 FROM learning_plans WHERE id='80000000-0000-0000-0000-000000000002'
   AND origin_kind='INTERVIEW_QUESTION' AND origin_resource_id=source_id AND origin_record_id='50000000-0000-0000-0000-000000000004') THEN RAISE EXCEPTION 'Question origin not resolved'; END IF;
 IF (SELECT count(*) FROM learning_plans WHERE id IN ('80000000-0000-0000-0000-000000000003','80000000-0000-0000-0000-000000000004','80000000-0000-0000-0000-000000000005')
   AND origin_kind='LEGACY_RECAP' AND origin_resource_id=source_id AND origin_record_id IS NULL) <> 3 THEN RAISE EXCEPTION 'Ambiguous/missing/foreign origins were guessed'; END IF;
 IF NOT EXISTS(SELECT 1 FROM learning_plans WHERE id='80000000-0000-0000-0000-000000000006' AND origin_kind='MANUAL' AND origin_resource_id IS NULL) THEN RAISE EXCEPTION 'Autonomous learning changed'; END IF;
 IF (SELECT count(*) FROM learning_plans WHERE user_id='50000000-0000-0000-0000-000000000001' AND target_interview_id IS NULL AND target_round_id IS NULL
   AND reason='Original reason' AND lesson='{"summary":"Original lesson"}'::jsonb AND exercises='[{"question":"Original exercise"}]'::jsonb
   AND scheduled_at='2099-01-01T09:00:00Z' AND attempt_count=2 AND last_score=61 AND status='NEEDS_REVIEW') <> 6 THEN RAISE EXCEPTION 'Historical content/progress changed or targets were inferred'; END IF;
END $$;

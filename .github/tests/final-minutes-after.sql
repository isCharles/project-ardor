BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM interview_recap_jobs WHERE id='59000000-0000-4000-8000-000000000003'
    AND NOT final_minutes AND raw_content='Legacy submitted text preserved exactly') THEN
    RAISE EXCEPTION 'V59 changed legacy mode or submitted text';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM background_task_catalog WHERE kind='RECAP_ORGANIZE'
    AND id='59000000-0000-4000-8000-000000000003'
    AND result_path='/app/recaps?draft=59000000-0000-4000-8000-000000000003') THEN
    RAISE EXCEPTION 'Legacy task link changed';
  END IF;
  UPDATE interview_recap_jobs SET final_minutes=TRUE WHERE id='59000000-0000-4000-8000-000000000003';
  IF (SELECT COUNT(*) FROM background_task_catalog WHERE kind='RECAP_ORGANIZE'
    AND id='59000000-0000-4000-8000-000000000003'
    AND user_id='59000000-0000-4000-8000-000000000001' AND title='Interview minutes'
    AND result_path='/app/recaps?minutes=59000000-0000-4000-8000-000000000003') <> 1 THEN
    RAISE EXCEPTION 'Final minutes task identity, owner or link not preserved';
  END IF;
END $$;
ROLLBACK;
DELETE FROM users WHERE id='59000000-0000-4000-8000-000000000001';

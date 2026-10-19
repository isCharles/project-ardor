\set ON_ERROR_STOP on
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='50' AND success) THEN
    RAISE EXCEPTION 'V50 was not applied by Flyway';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_recap_jobs WHERE id='50000000-0000-0000-0000-000000000005'
      AND raw_content='  RAW historical transcript, unchanged.  ' AND interview_round_id IS NULL
      AND job_opportunity_id='50000000-0000-0000-0000-000000000002') THEN
    RAISE EXCEPTION 'Historical transcript changed or a round was invented';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_recaps WHERE id='50000000-0000-0000-0000-000000000004'
      AND interview_round_id IS NULL AND overview='Preserved overview') THEN
    RAISE EXCEPTION 'Historical report changed or a round was invented';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_minutes_reviews WHERE id='50000000-0000-0000-0000-000000000006'
      AND revision=1 AND minutes='Preserved historical confirmed minutes.' AND status='COMPLETED') THEN
    RAISE EXCEPTION 'Historical confirmation snapshot changed';
  END IF;
END $$;

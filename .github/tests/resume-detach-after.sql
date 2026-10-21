BEGIN;
DELETE FROM resume_analyses WHERE id='54000000-0000-4000-8000-000000000021';
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM interview_sessions WHERE id='54000000-0000-4000-8000-000000000031' AND resume_analysis_id IS NULL) THEN
    RAISE EXCEPTION 'Deleted analysis must detach, not delete, its existing mock session';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_sessions WHERE id='54000000-0000-4000-8000-000000000032' AND resume_analysis_id='54000000-0000-4000-8000-000000000022') THEN
    RAISE EXCEPTION 'A different analysis version must remain associated';
  END IF;
END $$;
DELETE FROM resumes WHERE id='54000000-0000-4000-8000-000000000011';
DO $$ BEGIN
  IF (SELECT count(*) FROM interview_sessions WHERE id IN ('54000000-0000-4000-8000-000000000031','54000000-0000-4000-8000-000000000032') AND resume_analysis_id IS NULL) <> 2 THEN
    RAISE EXCEPTION 'Resume cascade must detach both sessions without deleting them';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_questions WHERE id='54000000-0000-4000-8000-000000000041' AND question_text='Synthetic history stays') THEN
    RAISE EXCEPTION 'Resume deletion must preserve question history';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM interview_sessions WHERE id='54000000-0000-4000-8000-000000000033' AND resume_analysis_id='54000000-0000-4000-8000-000000000023') THEN
    RAISE EXCEPTION 'Other owner must remain untouched';
  END IF;
  BEGIN
    INSERT INTO interview_sessions(id,user_id,resume_analysis_id,target_role) VALUES
      ('54000000-0000-4000-8000-000000000099','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000023','Forbidden cross-owner reference');
    RAISE EXCEPTION 'Composite owner foreign key must remain enforced';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
END $$;
ROLLBACK;

-- Resume deletion cascades resume_analyses, but an interview session may still
-- reference one of those analyses. Detach the session before the analysis row
-- is removed so deleting a resume cannot fail on the composite foreign key.
CREATE OR REPLACE FUNCTION detach_interview_session_resume_analysis()
RETURNS trigger AS $$
BEGIN
    UPDATE interview_sessions
    SET resume_analysis_id = NULL
    WHERE resume_analysis_id = OLD.id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_detach_interview_session_resume_analysis
BEFORE DELETE ON resume_analyses
FOR EACH ROW EXECUTE FUNCTION detach_interview_session_resume_analysis();

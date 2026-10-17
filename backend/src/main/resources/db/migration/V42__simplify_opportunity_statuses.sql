-- Collapse the status set to the four states the product actually uses.
-- V40 already ran, so this migration rewrites the check constraint and maps
-- any legacy values onto the closest surviving state.
ALTER TABLE job_opportunities DROP CONSTRAINT ck_job_opportunities_status;

UPDATE job_opportunities SET status = CASE status
    WHEN 'INVITED' THEN 'SCHEDULED'
    WHEN 'INTERVIEWED' THEN 'AWAITING_RESULT'
    WHEN 'OFFER' THEN 'AWAITING_RESULT'
    WHEN 'WITHDRAWN' THEN 'REJECTED'
    WHEN 'NO_RESPONSE' THEN 'REJECTED'
    ELSE status
END
WHERE status IN ('INVITED', 'INTERVIEWED', 'OFFER', 'WITHDRAWN', 'NO_RESPONSE');

ALTER TABLE job_opportunities ALTER COLUMN status SET DEFAULT 'SCHEDULED';
ALTER TABLE job_opportunities ADD CONSTRAINT ck_job_opportunities_status
    CHECK (status IN ('SCHEDULED', 'AWAITING_RESULT', 'NEXT_ROUND', 'REJECTED'));

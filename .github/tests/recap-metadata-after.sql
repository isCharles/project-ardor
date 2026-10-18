DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM interview_recaps r JOIN job_opportunities o
            ON o.id = r.job_opportunity_id AND o.user_id = r.user_id
        JOIN job_opportunity_rounds round ON round.opportunity_id = o.id AND round.user_id = o.user_id
        WHERE r.id = '71000000-0000-4000-8000-000000000002'
          AND o.company = 'Legacy fixture company' AND o.target_role = 'Original role'
          AND round.occurred_at = '2026-09-02T01:00:00Z'::timestamptz
    ) THEN RAISE EXCEPTION 'Legacy recap metadata was not preserved in its interview'; END IF;
    IF (SELECT count(*) FROM job_opportunity_rounds
        WHERE user_id = '71000000-0000-4000-8000-000000000001') <> 1 THEN
        RAISE EXCEPTION 'Legacy callback is not idempotent';
    END IF;
END $$;

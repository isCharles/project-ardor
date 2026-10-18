-- Runs before V43 without changing the checksum of any applied migration.
-- Move historical metadata to interview processes/rounds, not back onto documents.
-- On a database already at V43+, the old columns are absent: never invent lost data.
DO $$
DECLARE
    r RECORD;
    process_id UUID;
    legacy_round_id UUID;
BEGIN
    IF to_regclass('job_opportunities') IS NULL OR to_regclass('interview_recaps') IS NULL
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                      WHERE table_schema = current_schema() AND table_name = 'interview_recaps'
                        AND column_name = 'company') THEN
        RETURN;
    END IF;
    FOR r IN SELECT * FROM interview_recaps
             WHERE company IS NOT NULL OR target_role IS NOT NULL OR occurred_at IS NOT NULL
             ORDER BY id
    LOOP
        process_id := COALESCE(r.job_opportunity_id, md5('ardor:legacy-process:' || r.id::text)::uuid);
        IF r.job_opportunity_id IS NULL THEN
            INSERT INTO job_opportunities (id, user_id, company, target_role, status, created_at, updated_at)
            VALUES (process_id, r.user_id, COALESCE(NULLIF(btrim(r.company), ''), 'Imported interview'),
                    r.target_role, 'AWAITING_RESULT', r.created_at, r.updated_at)
            ON CONFLICT (id) DO NOTHING;
            UPDATE interview_recaps SET job_opportunity_id = process_id
            WHERE id = r.id AND user_id = r.user_id AND job_opportunity_id IS NULL;
        END IF;
        -- One idempotent historical round per document, even if already linked.
        -- Preserve conflicting old labels as a round note; never overwrite a user's process.
        legacy_round_id := md5('ardor:legacy-round:' || r.id::text)::uuid;
        IF NOT EXISTS (SELECT 1 FROM job_opportunity_rounds WHERE id = legacy_round_id) THEN
            INSERT INTO job_opportunity_rounds
                (id, user_id, opportunity_id, round_number, stage, occurred_at, note, created_at, updated_at)
            SELECT legacy_round_id, r.user_id, process_id, COALESCE(MAX(round_number), 0) + 1,
                   'OTHER', r.occurred_at,
                   concat('Imported legacy recap metadata; company: ', COALESCE(r.company, ''),
                          '; role: ', COALESCE(r.target_role, '')),
                   r.created_at, r.updated_at
            FROM job_opportunity_rounds WHERE opportunity_id = process_id AND user_id = r.user_id;
        END IF;
    END LOOP;
END $$;

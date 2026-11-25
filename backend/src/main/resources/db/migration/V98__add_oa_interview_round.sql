ALTER TABLE job_opportunity_rounds
    DROP CONSTRAINT ck_job_opportunity_stage;

ALTER TABLE job_opportunity_rounds
    ADD CONSTRAINT ck_job_opportunity_stage
    CHECK (stage IN ('HR', 'TECHNICAL', 'MANAGER', 'CROSS', 'FINAL', 'OA', 'OTHER'));

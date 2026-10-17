-- A real interview process, distinct from mock sessions (practice) and recaps
-- (post-hoc review). One row per company/role process; each round is an event.
CREATE TABLE job_opportunities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    company VARCHAR(160) NOT NULL,
    target_role VARCHAR(160),
    platform VARCHAR(80),
    jd_content TEXT,
    invite_content TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'INVITED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_opportunities_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_job_opportunities_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_job_opportunities_status CHECK (status IN (
        'INVITED', 'SCHEDULED', 'INTERVIEWED', 'AWAITING_RESULT',
        'OFFER', 'REJECTED', 'WITHDRAWN', 'NO_RESPONSE'))
);
CREATE INDEX ix_job_opportunities_user_status
    ON job_opportunities (user_id, status, updated_at DESC);

CREATE TABLE job_opportunity_rounds (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    opportunity_id UUID NOT NULL,
    round_number INTEGER NOT NULL,
    stage VARCHAR(32) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ,
    duration_minutes INTEGER,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_opportunity_rounds_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_job_opportunity_rounds_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_opportunity_rounds_owner FOREIGN KEY (opportunity_id, user_id)
        REFERENCES job_opportunities (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_job_opportunity_round_number UNIQUE (opportunity_id, round_number),
    CONSTRAINT ck_job_opportunity_round_number CHECK (round_number > 0),
    CONSTRAINT ck_job_opportunity_duration CHECK (duration_minutes IS NULL OR duration_minutes BETWEEN 1 AND 480),
    CONSTRAINT ck_job_opportunity_stage CHECK (stage IN (
        'HR', 'TECHNICAL', 'MANAGER', 'CROSS', 'FINAL', 'OTHER'))
);
CREATE INDEX ix_job_opportunity_rounds_user_schedule
    ON job_opportunity_rounds (user_id, scheduled_at);

-- Optional link. A recap may belong to a process; removing the process keeps the
-- review it produced. Mock-session and calendar links are added when those write
-- paths actually use them, not pre-created here.
ALTER TABLE interview_recaps ADD COLUMN job_opportunity_id UUID;
ALTER TABLE interview_recaps ADD CONSTRAINT fk_interview_recaps_opportunity_owner
    FOREIGN KEY (job_opportunity_id, user_id) REFERENCES job_opportunities (id, user_id)
    ON DELETE SET NULL (job_opportunity_id);
CREATE INDEX ix_interview_recaps_user_opportunity
    ON interview_recaps (user_id, job_opportunity_id);

-- The async recap job carries the process so the finished recap attaches itself.
ALTER TABLE interview_recap_jobs ADD COLUMN job_opportunity_id UUID;
ALTER TABLE interview_recap_jobs ADD CONSTRAINT fk_interview_recap_jobs_opportunity_owner
    FOREIGN KEY (job_opportunity_id, user_id) REFERENCES job_opportunities (id, user_id)
    ON DELETE SET NULL (job_opportunity_id);
CREATE INDEX ix_interview_recap_jobs_user_opportunity
    ON interview_recap_jobs (user_id, job_opportunity_id);

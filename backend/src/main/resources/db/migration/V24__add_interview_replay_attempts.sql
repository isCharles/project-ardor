CREATE TABLE interview_replay_attempts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    recap_question_id UUID NOT NULL,
    request_id UUID NOT NULL,
    answer_text TEXT NOT NULL,
    verdict VARCHAR(24) NOT NULL,
    comparison TEXT NOT NULL,
    improvements JSONB NOT NULL DEFAULT '[]'::jsonb,
    remaining_gaps JSONB NOT NULL DEFAULT '[]'::jsonb,
    next_challenge TEXT,
    model_name VARCHAR(120),
    prompt_version VARCHAR(40) NOT NULL DEFAULT 'interview-replay-v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_replay_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_replay_question_owner FOREIGN KEY (recap_question_id, user_id)
        REFERENCES interview_recap_questions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_replay_request_owner UNIQUE (user_id, request_id),
    CONSTRAINT ck_replay_verdict CHECK (verdict IN ('CLEARER', 'SIMILAR', 'NEEDS_WORK', 'UNKNOWN'))
);

CREATE INDEX ix_replay_user_question_time
    ON interview_replay_attempts (user_id, recap_question_id, created_at DESC);

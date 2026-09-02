CREATE TABLE interview_recaps (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(240) NOT NULL,
    company VARCHAR(160),
    target_role VARCHAR(160),
    occurred_at TIMESTAMPTZ,
    source_type VARCHAR(32) NOT NULL,
    raw_content TEXT NOT NULL,
    input_hash CHAR(64) NOT NULL,
    overview TEXT NOT NULL,
    strengths JSONB NOT NULL DEFAULT '[]'::jsonb,
    weaknesses JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_name VARCHAR(120),
    prompt_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_recaps_id_owner UNIQUE (id, user_id),
    CONSTRAINT uq_interview_recaps_user_input UNIQUE (user_id, input_hash),
    CONSTRAINT fk_interview_recaps_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_interview_recaps_source CHECK (source_type IN ('TRANSCRIPT', 'RECOLLECTION', 'NOTES'))
);
CREATE INDEX ix_interview_recaps_user_created ON interview_recaps (user_id, created_at DESC);

CREATE TABLE interview_recap_questions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    recap_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    candidate_answer TEXT,
    follow_ups JSONB NOT NULL DEFAULT '[]'::jsonb,
    assessment TEXT NOT NULL,
    performance VARCHAR(32) NOT NULL,
    weakness_reason TEXT,
    better_answer TEXT,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_recap_questions_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_recap_questions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_recap_questions_recap_owner FOREIGN KEY (recap_id, user_id) REFERENCES interview_recaps (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_recap_question_sequence UNIQUE (recap_id, sequence_number),
    CONSTRAINT ck_interview_recap_question_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_interview_recap_question_performance CHECK (performance IN ('STRONG', 'MIXED', 'WEAK', 'UNKNOWN'))
);
CREATE INDEX ix_interview_recap_questions_user_recap ON interview_recap_questions (user_id, recap_id, sequence_number);

CREATE TABLE memory_cards (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    recap_question_id UUID,
    source_type VARCHAR(32) NOT NULL,
    source_label VARCHAR(240),
    source_url VARCHAR(1000),
    front TEXT NOT NULL,
    back TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    next_review_at TIMESTAMPTZ NOT NULL,
    interval_days INTEGER NOT NULL DEFAULT 0,
    ease_factor NUMERIC(4, 2) NOT NULL DEFAULT 2.50,
    repetitions INTEGER NOT NULL DEFAULT 0,
    lapses INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_memory_cards_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_memory_cards_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_cards_recap_question_owner FOREIGN KEY (recap_question_id, user_id) REFERENCES interview_recap_questions (id, user_id) ON DELETE SET NULL (recap_question_id),
    CONSTRAINT uq_memory_cards_recap_question UNIQUE (recap_question_id),
    CONSTRAINT ck_memory_cards_source CHECK (source_type IN ('INTERVIEW', 'KNOWLEDGE', 'AGENT', 'WEB')),
    CONSTRAINT ck_memory_cards_status CHECK (status IN ('NEW', 'LEARNING', 'REVIEW', 'SUSPENDED')),
    CONSTRAINT ck_memory_cards_interval CHECK (interval_days >= 0),
    CONSTRAINT ck_memory_cards_repetitions CHECK (repetitions >= 0 AND lapses >= 0)
);
CREATE INDEX ix_memory_cards_user_due ON memory_cards (user_id, status, next_review_at);

CREATE TABLE memory_card_reviews (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    memory_card_id UUID NOT NULL,
    rating VARCHAR(16) NOT NULL,
    previous_interval_days INTEGER NOT NULL,
    next_interval_days INTEGER NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_card_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_card_reviews_card_owner FOREIGN KEY (memory_card_id, user_id) REFERENCES memory_cards (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_memory_card_reviews_rating CHECK (rating IN ('AGAIN', 'HARD', 'GOOD', 'EASY'))
);
CREATE INDEX ix_memory_card_reviews_user_card ON memory_card_reviews (user_id, memory_card_id, reviewed_at DESC);

ALTER TABLE tasks ADD COLUMN memory_card_id UUID;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_memory_card_owner
    FOREIGN KEY (memory_card_id, user_id) REFERENCES memory_cards (id, user_id) ON DELETE SET NULL (memory_card_id);
CREATE INDEX ix_tasks_user_memory_card ON tasks (user_id, memory_card_id);

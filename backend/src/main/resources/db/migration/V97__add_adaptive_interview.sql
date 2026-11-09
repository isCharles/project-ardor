-- Adaptive mock interviews reuse the classic session/question/answer/evaluation rows.
-- mode decides who drives the session: CLASSIC keeps the pre-generated fixed paper,
-- ADAPTIVE lets the Java policy engine append one question at a time. Existing rows
-- become CLASSIC, so the classic read path and its wire contract are unchanged.

ALTER TABLE interview_sessions
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
    ADD COLUMN seniority VARCHAR(64),
    ADD COLUMN duration_minutes INTEGER,
    ADD COLUMN deep_dive BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN job_description TEXT;
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_mode CHECK (mode IN ('CLASSIC', 'ADAPTIVE')),
    ADD CONSTRAINT ck_interview_sessions_duration CHECK (duration_minutes IS NULL OR duration_minutes > 0);

-- A question carries the competency it probes and the rubric written before it was asked.
-- Classic questions leave all of these null; evaluation_criteria stays their only rubric.
ALTER TABLE interview_questions
    ADD COLUMN competency VARCHAR(160),
    ADD COLUMN knowledge_point VARCHAR(200),
    ADD COLUMN difficulty SMALLINT,
    ADD COLUMN rubric JSONB,
    ADD COLUMN origin VARCHAR(24),
    ADD COLUMN parent_question_id UUID,
    ADD COLUMN difficulty_feedback VARCHAR(16);
ALTER TABLE interview_questions
    ADD CONSTRAINT ck_interview_questions_difficulty CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
    ADD CONSTRAINT ck_interview_questions_origin CHECK (
        origin IS NULL OR origin IN ('PLAN', 'FOLLOW_UP', 'ALTERNATE', 'BANK', 'CLARIFICATION')),
    ADD CONSTRAINT ck_interview_questions_feedback CHECK (
        difficulty_feedback IS NULL OR difficulty_feedback IN ('TOO_HARD', 'TOO_EASY', 'FITTING')),
    ADD CONSTRAINT fk_interview_questions_parent_owner FOREIGN KEY (parent_question_id, user_id)
        REFERENCES interview_questions (id, user_id) ON DELETE CASCADE;
CREATE INDEX ix_interview_questions_competency
    ON interview_questions (user_id, interview_session_id, competency, difficulty);

-- An answer that could not be scored stays recoverable: the next state read retries it
-- until the attempt ceiling, instead of losing the turn or blocking the session.
ALTER TABLE interview_answers
    ADD COLUMN evaluation_attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN evaluation_error VARCHAR(64);
ALTER TABLE interview_answers
    ADD CONSTRAINT ck_interview_answers_attempts CHECK (evaluation_attempts >= 0);

CREATE TABLE interview_plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    plan JSONB NOT NULL,
    model_name VARCHAR(120),
    prompt_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_plans_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_plans_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_plans_session_owner FOREIGN KEY (interview_session_id, user_id)
        REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_plans_session UNIQUE (interview_session_id)
);
CREATE INDEX ix_interview_plans_user_session ON interview_plans (user_id, interview_session_id);

-- One scored turn per answer. score is null only for a clarification request, which is
-- answered again rather than judged; confidence is what keeps a shaky reading from
-- lowering the candidate's estimated level.
CREATE TABLE interview_turn_evaluations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    interview_question_id UUID NOT NULL,
    interview_answer_id UUID NOT NULL,
    answer_kind VARCHAR(24) NOT NULL,
    score SMALLINT,
    confidence NUMERIC(3, 2),
    evaluation JSONB,
    model_name VARCHAR(120),
    prompt_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_turn_evaluations_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_turn_evaluations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_turn_evaluations_session_owner FOREIGN KEY (interview_session_id, user_id)
        REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_turn_evaluations_question_owner FOREIGN KEY (interview_question_id, user_id)
        REFERENCES interview_questions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_turn_evaluations_answer_owner FOREIGN KEY (interview_answer_id, user_id)
        REFERENCES interview_answers (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_turn_evaluations_question UNIQUE (interview_question_id),
    CONSTRAINT uq_interview_turn_evaluations_answer UNIQUE (interview_answer_id),
    CONSTRAINT ck_interview_turn_evaluations_kind CHECK (
        answer_kind IN ('ANSWER', 'CLARIFICATION', 'OFF_TOPIC', 'UNKNOWN')),
    CONSTRAINT ck_interview_turn_evaluations_score CHECK (score IS NULL OR score BETWEEN 0 AND 4),
    CONSTRAINT ck_interview_turn_evaluations_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT ck_interview_turn_evaluations_clarification CHECK (
        (answer_kind = 'CLARIFICATION' AND score IS NULL AND confidence IS NULL)
        OR (answer_kind <> 'CLARIFICATION' AND score IS NOT NULL AND confidence IS NOT NULL))
);
CREATE INDEX ix_interview_turn_evaluations_user_session
    ON interview_turn_evaluations (user_id, interview_session_id, created_at);

-- Append-only log of every deterministic policy decision, with the inputs it read.
-- Replaying these rows reproduces the interview without asking the model again.
CREATE TABLE interview_policy_decisions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    interview_question_id UUID,
    action VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    decision JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_policy_decisions_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_policy_decisions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_policy_decisions_session_owner FOREIGN KEY (interview_session_id, user_id)
        REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_policy_decisions_question_owner FOREIGN KEY (interview_question_id, user_id)
        REFERENCES interview_questions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_policy_decisions_sequence UNIQUE (interview_session_id, sequence_number),
    CONSTRAINT ck_interview_policy_decisions_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_interview_policy_decisions_action CHECK (
        action IN ('OPEN', 'RAISE', 'HOLD', 'LOWER', 'CLARIFY', 'SWITCH', 'TERMINATE'))
);
CREATE INDEX ix_interview_policy_decisions_user_session
    ON interview_policy_decisions (user_id, interview_session_id, sequence_number);

-- estimated_level is null until evidence supports one: an untested competency is
-- UNVERIFIED, never "cannot do". GAP_EXPOSED means a real answer showed the boundary.
CREATE TABLE interview_competency_assessments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    competency VARCHAR(160) NOT NULL,
    jd_priority SMALLINT NOT NULL DEFAULT 3,
    estimated_level SMALLINT,
    confidence NUMERIC(3, 2) NOT NULL DEFAULT 0,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    strengths JSONB NOT NULL DEFAULT '[]'::jsonb,
    knowledge_gaps JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_competency_assessments_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_competency_assessments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_competency_assessments_session_owner FOREIGN KEY (interview_session_id, user_id)
        REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_competency_assessments_session_skill UNIQUE (interview_session_id, competency),
    CONSTRAINT ck_interview_competency_assessments_priority CHECK (jd_priority BETWEEN 1 AND 5),
    CONSTRAINT ck_interview_competency_assessments_level CHECK (estimated_level IS NULL OR estimated_level BETWEEN 1 AND 5),
    CONSTRAINT ck_interview_competency_assessments_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_interview_competency_assessments_evidence CHECK (evidence_count >= 0),
    CONSTRAINT ck_interview_competency_assessments_state CHECK (state IN ('UNVERIFIED', 'VERIFIED', 'GAP_EXPOSED'))
);
CREATE INDEX ix_interview_competency_assessments_user_session
    ON interview_competency_assessments (user_id, interview_session_id);
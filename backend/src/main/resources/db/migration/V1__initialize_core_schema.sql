CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    display_name VARCHAR(120),
    headline VARCHAR(240),
    target_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_profiles_user UNIQUE (user_id),
    CONSTRAINT uq_user_profiles_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE resumes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    parsed_text TEXT,
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resumes_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_resumes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_resumes_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_resumes_parse_status CHECK (parse_status IN ('PENDING', 'PARSED', 'FAILED'))
);
CREATE INDEX ix_resumes_user_created ON resumes (user_id, created_at DESC);

CREATE TABLE resume_analyses (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    resume_id UUID NOT NULL,
    analysis JSONB NOT NULL,
    model_name VARCHAR(120),
    prompt_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_resume_analyses_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_resume_analyses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_analyses_resume_owner FOREIGN KEY (resume_id, user_id) REFERENCES resumes (id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_resume_analyses_user_resume ON resume_analyses (user_id, resume_id, created_at DESC);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(240),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    context JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversations_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_conversations_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);
CREATE INDEX ix_conversations_user_updated ON conversations (user_id, updated_at DESC);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    tool_calls JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_messages_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_messages_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_conversation_owner FOREIGN KEY (conversation_id, user_id) REFERENCES conversations (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_messages_role CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM'))
);
CREATE INDEX ix_messages_user_conversation ON messages (user_id, conversation_id, created_at);

CREATE TABLE interview_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    resume_analysis_id UUID,
    modality VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    target_company VARCHAR(160),
    target_role VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_sessions_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_sessions_analysis_owner FOREIGN KEY (resume_analysis_id, user_id) REFERENCES resume_analyses (id, user_id),
    CONSTRAINT ck_interview_sessions_modality CHECK (modality IN ('TEXT', 'VOICE')),
    CONSTRAINT ck_interview_sessions_status CHECK (status IN ('CREATED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_interview_sessions_user_created ON interview_sessions (user_id, created_at DESC);

CREATE TABLE interview_questions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type VARCHAR(64),
    evaluation_criteria JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_questions_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_questions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_questions_session_owner FOREIGN KEY (interview_session_id, user_id) REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_questions_sequence UNIQUE (interview_session_id, sequence_number),
    CONSTRAINT ck_interview_questions_sequence CHECK (sequence_number > 0)
);
CREATE INDEX ix_interview_questions_user_session ON interview_questions (user_id, interview_session_id, sequence_number);

CREATE TABLE interview_answers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    interview_question_id UUID NOT NULL,
    answer_text TEXT NOT NULL,
    duration_seconds INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_answers_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_answers_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_answers_session_owner FOREIGN KEY (interview_session_id, user_id) REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_answers_question_owner FOREIGN KEY (interview_question_id, user_id) REFERENCES interview_questions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_answers_question UNIQUE (interview_question_id),
    CONSTRAINT ck_interview_answers_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);
CREATE INDEX ix_interview_answers_user_session ON interview_answers (user_id, interview_session_id, created_at);

CREATE TABLE interview_evaluations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    interview_session_id UUID NOT NULL,
    overall_score NUMERIC(5, 2) NOT NULL,
    evaluation JSONB NOT NULL,
    model_name VARCHAR(120),
    prompt_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_evaluations_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_interview_evaluations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_evaluations_session_owner FOREIGN KEY (interview_session_id, user_id) REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_interview_evaluations_session UNIQUE (interview_session_id),
    CONSTRAINT ck_interview_evaluations_score CHECK (overall_score >= 0 AND overall_score <= 100)
);
CREATE INDEX ix_interview_evaluations_user_session ON interview_evaluations (user_id, interview_session_id);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_interview_id UUID,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'TODO',
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tasks_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_source_interview_owner FOREIGN KEY (source_interview_id, user_id) REFERENCES interview_sessions (id, user_id),
    CONSTRAINT ck_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_tasks_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'))
);
CREATE INDEX ix_tasks_user_status_due ON tasks (user_id, status, due_at);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    task_id UUID,
    title VARCHAR(240) NOT NULL,
    description TEXT,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_events_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_events_task_owner FOREIGN KEY (task_id, user_id) REFERENCES tasks (id, user_id),
    CONSTRAINT ck_events_time_range CHECK (ends_at > starts_at)
);
CREATE INDEX ix_events_user_starts ON events (user_id, starts_at);

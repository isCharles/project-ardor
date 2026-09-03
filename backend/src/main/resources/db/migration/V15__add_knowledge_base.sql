CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    title VARCHAR(240) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_url VARCHAR(2048),
    original_filename VARCHAR(255),
    content_type VARCHAR(160),
    raw_content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_documents_id_owner UNIQUE (id, user_id),
    CONSTRAINT fk_knowledge_documents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_documents_source CHECK (source_type IN ('USER_UPLOAD', 'WEB'))
);
CREATE INDEX ix_knowledge_documents_user_created ON knowledge_documents (user_id, created_at DESC);
CREATE UNIQUE INDEX uq_knowledge_documents_user_url
    ON knowledge_documents (user_id, source_url) WHERE source_url IS NOT NULL;

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    document_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_chunks_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_chunks_document_owner FOREIGN KEY (document_id, user_id)
        REFERENCES knowledge_documents (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_knowledge_chunks_sequence UNIQUE (document_id, sequence_number),
    CONSTRAINT ck_knowledge_chunks_sequence CHECK (sequence_number > 0)
);
CREATE INDEX ix_knowledge_chunks_user_document ON knowledge_chunks (user_id, document_id, sequence_number);

ALTER TABLE tasks DROP CONSTRAINT fk_tasks_source_interview_owner;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_source_interview_owner
    FOREIGN KEY (source_interview_id, user_id) REFERENCES interview_sessions (id, user_id) ON DELETE CASCADE;

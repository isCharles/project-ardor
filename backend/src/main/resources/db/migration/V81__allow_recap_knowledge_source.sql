-- Interview recaps become a retrievable source inside the existing knowledge pipeline.
-- Numbered V81 rather than V79 because V80 had already been applied to the daily database.
ALTER TABLE knowledge_documents DROP CONSTRAINT ck_knowledge_documents_source;
ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_source
    CHECK (source_type IN ('USER_UPLOAD', 'WEB', 'RECAP'));

-- rollback ALTER TABLE knowledge_documents DROP CONSTRAINT ck_knowledge_documents_source;
-- rollback ALTER TABLE knowledge_documents ADD CONSTRAINT ck_knowledge_documents_source
-- rollback     CHECK (source_type IN ('USER_UPLOAD', 'WEB'));

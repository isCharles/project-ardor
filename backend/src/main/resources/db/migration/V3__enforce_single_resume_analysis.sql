ALTER TABLE resume_analyses
    ADD CONSTRAINT uq_resume_analyses_user_resume UNIQUE (user_id, resume_id);

ALTER TABLE llm_provider_configs
    DROP CONSTRAINT ck_llm_provider_configs_provider;

UPDATE llm_provider_configs
SET provider = 'OPENAI_COMPATIBLE'
WHERE provider = 'OPENAI';

ALTER TABLE llm_provider_configs
    ADD CONSTRAINT ck_llm_provider_configs_provider
        CHECK (provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC_COMPATIBLE'));

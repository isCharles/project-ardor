-- The FREE weekly allowances shipped in V68 were briefly widened so synthetic acceptance
-- runs would not be quota-blocked. That made a test fixture part of real product policy,
-- so re-assert the intended allowances for every database, whether or not it saw that edit.
UPDATE usage_policies SET
    free_weekly_limit = CASE feature
        WHEN 'AGENT_CHAT' THEN 2
        WHEN 'RESUME_ANALYSIS' THEN 1
        WHEN 'INTERVIEW_CREATE' THEN 1
        WHEN 'INTERVIEW_EVALUATION' THEN 1
        WHEN 'INTERVIEW_RECAP' THEN 1
        WHEN 'INTERVIEW_REPLAY' THEN 1
        WHEN 'LEARNING_PLAN' THEN 1
        WHEN 'LEARNING_ATTEMPT' THEN 1
        WHEN 'KNOWLEDGE_UPLOAD' THEN 1
        WHEN 'KNOWLEDGE_SEARCH' THEN 5
        WHEN 'KNOWLEDGE_RESEARCH' THEN 1
        WHEN 'CONNECTION_TEST' THEN 2
        WHEN 'VOICE_ASR' THEN 1
        WHEN 'VOICE_TTS' THEN 1
        ELSE 0
    END;

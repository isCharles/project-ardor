INSERT INTO users(id, email, password_hash)
VALUES ('71000000-0000-4000-8000-000000000001', 'legacy-upgrade@ardor.invalid', 'not-a-login-hash');
INSERT INTO interview_recaps
    (id, user_id, title, company, target_role, occurred_at, source_type, raw_content, input_hash, overview, prompt_version)
VALUES ('71000000-0000-4000-8000-000000000002', '71000000-0000-4000-8000-000000000001',
        'Legacy fixture', 'Legacy fixture company', 'Original role', '2026-09-02T01:00:00Z',
        'NOTES', 'Synthetic content without its corrected metadata', repeat('7', 64), 'Fixture overview', 'fixture');

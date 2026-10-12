DO $$ BEGIN
    IF (SELECT title_manually_set FROM conversations WHERE id = '32000000-0000-0000-0000-000000000011')
       OR (SELECT title_manually_set FROM conversations WHERE id = '32000000-0000-0000-0000-000000000013')
       OR (SELECT title_manually_set FROM conversations WHERE id = '32000000-0000-0000-0000-000000000014') THEN
        RAISE EXCEPTION 'Former automatic titles must remain automatic';
    END IF;
    IF NOT (SELECT title_manually_set FROM conversations WHERE id = '32000000-0000-0000-0000-000000000012')
       OR (SELECT title FROM conversations WHERE id = '32000000-0000-0000-0000-000000000012') <> '自己起的题目' THEN
        RAISE EXCEPTION 'An existing manual title must be preserved';
    END IF;
END $$;
INSERT INTO conversations (id, user_id, title)
VALUES ('32000000-0000-0000-0000-000000000015', '32000000-0000-0000-0000-000000000001', '新对话');
DO $$ BEGIN
    IF (SELECT title_manually_set FROM conversations WHERE id = '32000000-0000-0000-0000-000000000015') THEN
        RAISE EXCEPTION 'New conversations should default to automatic titles';
    END IF;
END $$;

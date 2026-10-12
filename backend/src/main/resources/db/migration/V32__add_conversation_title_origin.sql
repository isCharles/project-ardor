-- Preserve existing custom titles; recognize the former first-message title format.
ALTER TABLE conversations ADD COLUMN title_manually_set BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE conversations c SET title_manually_set = FALSE
WHERE title IS NULL OR btrim(title) = '' OR title = '新对话'
   OR title = (
       SELECT CASE WHEN length(candidate) > 24 THEN left(candidate, 24) || '…' ELSE candidate END
       FROM (
           SELECT btrim((regexp_split_to_array(regexp_replace(btrim(m.content), '\s+', ' ', 'g'), '[。！？!?；;]'))[1]) AS candidate
           FROM messages m WHERE m.conversation_id = c.id AND m.user_id = c.user_id AND m.role = 'USER'
           ORDER BY m.created_at ASC, m.id ASC LIMIT 1
       ) first_message
   );
ALTER TABLE conversations ALTER COLUMN title_manually_set SET DEFAULT FALSE;

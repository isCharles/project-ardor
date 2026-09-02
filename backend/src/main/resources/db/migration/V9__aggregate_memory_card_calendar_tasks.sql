ALTER TABLE tasks ADD COLUMN task_kind VARCHAR(32) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE tasks ADD COLUMN review_date DATE;
ALTER TABLE tasks ADD COLUMN action_path VARCHAR(512);
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_kind CHECK (task_kind IN ('GENERAL', 'MEMORY_REVIEW'));

DELETE FROM tasks WHERE memory_card_id IS NOT NULL;

INSERT INTO tasks (
    id, user_id, title, description, status, priority, due_at, source,
    task_kind, review_date, action_path, created_at, updated_at
)
SELECT
    gen_random_uuid(), user_id, '记忆卡复习', COUNT(*) || ' 张待复习', 'TODO', 'HIGH',
    MIN(next_review_at), 'AGENT', 'MEMORY_REVIEW',
    (next_review_at AT TIME ZONE 'Asia/Shanghai')::date, '/app/cards', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM memory_cards
WHERE status <> 'SUSPENDED'
GROUP BY user_id, (next_review_at AT TIME ZONE 'Asia/Shanghai')::date;

CREATE UNIQUE INDEX uq_tasks_memory_review_day
    ON tasks (user_id, task_kind, review_date)
    WHERE task_kind = 'MEMORY_REVIEW';

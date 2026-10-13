INSERT INTO background_task_reads(user_id,kind,task_id,completion_token)
VALUES ('32000000-0000-0000-0000-000000000001','LEARNING_CREATE',
        '34000000-0000-0000-0000-000000000001','older-read-result');
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM background_task_reads WHERE task_id='34000000-0000-0000-0000-000000000001')<>2 THEN
        RAISE EXCEPTION 'Different read-result versions must coexist';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM background_task_catalog c JOIN background_task_reads r
        ON r.user_id=c.user_id AND r.kind=c.kind AND r.task_id=c.id AND r.completion_token=c.completion_token
        WHERE c.id='34000000-0000-0000-0000-000000000001') THEN
        RAISE EXCEPTION 'Older read must not erase current read result';
    END IF;
END $$;

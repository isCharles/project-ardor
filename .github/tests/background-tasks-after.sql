DO $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM background_task_reads WHERE task_id='34000000-0000-0000-0000-000000000001') THEN
        RAISE EXCEPTION 'Historical completed results must not become new notifications';
    END IF;
    IF (SELECT COUNT(*) FROM background_task_catalog WHERE id='34000000-0000-0000-0000-000000000001')<>1 THEN
        RAISE EXCEPTION 'Catalog must preserve existing jobs';
    END IF;
END $$;

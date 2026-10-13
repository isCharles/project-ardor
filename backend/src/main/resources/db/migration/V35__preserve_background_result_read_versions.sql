-- A late acknowledgment of an old result must not overwrite a newer read result.
ALTER TABLE background_task_reads DROP CONSTRAINT background_task_reads_pkey;
ALTER TABLE background_task_reads ADD PRIMARY KEY(user_id,kind,task_id,completion_token);

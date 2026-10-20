-- Run after the shared interview-record fixture, in an isolated V50 database only.
INSERT INTO users(id,email,password_hash) VALUES ('70000000-0000-0000-0000-000000000009','foreign-origin@ardor.invalid','not-a-login-hash');
INSERT INTO interview_recaps(id,user_id,title,source_type,raw_content,input_hash,overview,prompt_version) VALUES
 ('70000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000001','Synthetic collision record','NOTES','Synthetic source',repeat('c',64),'Overview','fixture'),
 ('70000000-0000-0000-0000-000000000004','70000000-0000-0000-0000-000000000009','Synthetic foreign record','NOTES','Synthetic source',repeat('d',64),'Overview','fixture');
INSERT INTO interview_recap_questions(id,user_id,recap_id,sequence_number,question_text,assessment,performance) VALUES
 ('70000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000004',1,'Normal question','Original assessment','WEAK'),
 ('70000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000004',2,'Collision question','Original assessment','WEAK'),
 ('70000000-0000-0000-0000-000000000003','70000000-0000-0000-0000-000000000009','70000000-0000-0000-0000-000000000004',1,'Foreign question','Original assessment','WEAK');
INSERT INTO learning_plans(id,user_id,concept,reason,source_type,source_id,status,lesson,exercises,attempt_count,last_score,scheduled_at)
SELECT ('80000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid,
 '50000000-0000-0000-0000-000000000001','Historical learning '||n,'Original reason',
 CASE WHEN n=6 THEN 'MANUAL' ELSE 'RECAP' END,
 CASE n WHEN 1 THEN '50000000-0000-0000-0000-000000000004'::uuid
        WHEN 2 THEN '70000000-0000-0000-0000-000000000001'::uuid
        WHEN 3 THEN '70000000-0000-0000-0000-000000000002'::uuid
        WHEN 4 THEN '70000000-0000-0000-0000-000000000099'::uuid
        WHEN 5 THEN '70000000-0000-0000-0000-000000000003'::uuid ELSE NULL END,
 'NEEDS_REVIEW','{"summary":"Original lesson"}'::jsonb,'[{"question":"Original exercise"}]'::jsonb,2,61,'2099-01-01T09:00:00Z'
FROM generate_series(1,6) n;

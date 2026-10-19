-- Disposable V49 database only. All identities and text are synthetic.
INSERT INTO users(id,email,password_hash) VALUES ('50000000-0000-0000-0000-000000000001','round-upgrade@ardor.invalid','not-a-login-hash');
INSERT INTO job_opportunities(id,user_id,company,status) VALUES
 ('50000000-0000-0000-0000-000000000002','50000000-0000-0000-0000-000000000001','Synthetic historical process','SCHEDULED');
INSERT INTO job_opportunity_rounds(id,user_id,opportunity_id,round_number,stage) VALUES
 ('50000000-0000-0000-0000-000000000003','50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002',1,'TECHNICAL');
INSERT INTO interview_recaps(id,user_id,title,source_type,raw_content,input_hash,overview,prompt_version,job_opportunity_id) VALUES
 ('50000000-0000-0000-0000-000000000004','50000000-0000-0000-0000-000000000001','Historical synthetic report','NOTES','Preserved historical confirmed minutes.',repeat('a',64),'Preserved overview','confirmed-minutes-review-v1','50000000-0000-0000-0000-000000000002');
INSERT INTO interview_recap_jobs(id,user_id,recap_id,input_hash,raw_content,status,job_opportunity_id) VALUES
 ('50000000-0000-0000-0000-000000000005','50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000004',repeat('b',64),'  RAW historical transcript, unchanged.  ','COMPLETED','50000000-0000-0000-0000-000000000002');
INSERT INTO interview_minutes(id,user_id,minutes) VALUES
 ('50000000-0000-0000-0000-000000000005','50000000-0000-0000-0000-000000000001','Preserved historical confirmed minutes.');
INSERT INTO interview_minutes_reviews(id,draft_id,user_id,revision,minutes,status,recap_id) VALUES
 ('50000000-0000-0000-0000-000000000006','50000000-0000-0000-0000-000000000005','50000000-0000-0000-0000-000000000001',1,'Preserved historical confirmed minutes.','COMPLETED','50000000-0000-0000-0000-000000000004');

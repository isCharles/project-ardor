INSERT INTO users(id,email,password_hash) VALUES
('59000000-0000-4000-8000-000000000001','final-minutes-upgrade@ardor.invalid','not-a-login-hash');
INSERT INTO job_opportunities(id,user_id,company) VALUES
('59000000-0000-4000-8000-000000000002','59000000-0000-4000-8000-000000000001','Upgrade sentinel');
INSERT INTO interview_recap_jobs(id,user_id,input_hash,raw_content,status,job_opportunity_id) VALUES
('59000000-0000-4000-8000-000000000003','59000000-0000-4000-8000-000000000001',repeat('9',64),'Legacy submitted text preserved exactly','QUEUED','59000000-0000-4000-8000-000000000002');

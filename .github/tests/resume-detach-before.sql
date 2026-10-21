-- Synthetic V53 state: two versions, two owners, and existing mock interview history.
INSERT INTO users(id,email,password_hash) VALUES
('54000000-0000-4000-8000-000000000001','detach-one@ardor.invalid','not-a-login-hash'),
('54000000-0000-4000-8000-000000000002','detach-two@ardor.invalid','not-a-login-hash');
INSERT INTO resumes(id,user_id,original_filename,content_type,storage_key,size_bytes,checksum_sha256) VALUES
('54000000-0000-4000-8000-000000000011','54000000-0000-4000-8000-000000000001','fixture-one.pdf','application/pdf','fixture-one',0,repeat('0',64)),
('54000000-0000-4000-8000-000000000012','54000000-0000-4000-8000-000000000002','fixture-two.pdf','application/pdf','fixture-two',0,repeat('0',64));
INSERT INTO resume_analyses(id,user_id,resume_id,analysis,prompt_version,evaluation_key) VALUES
('54000000-0000-4000-8000-000000000021','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000011','{}','fixture',repeat('a',64)),
('54000000-0000-4000-8000-000000000022','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000011','{}','fixture',repeat('b',64)),
('54000000-0000-4000-8000-000000000023','54000000-0000-4000-8000-000000000002','54000000-0000-4000-8000-000000000012','{}','fixture',repeat('c',64));
INSERT INTO interview_sessions(id,user_id,resume_analysis_id,target_role) VALUES
('54000000-0000-4000-8000-000000000031','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000021','Synthetic role'),
('54000000-0000-4000-8000-000000000032','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000022','Synthetic role'),
('54000000-0000-4000-8000-000000000033','54000000-0000-4000-8000-000000000002','54000000-0000-4000-8000-000000000023','Synthetic role');
INSERT INTO interview_questions(id,user_id,interview_session_id,sequence_number,question_text) VALUES
('54000000-0000-4000-8000-000000000041','54000000-0000-4000-8000-000000000001','54000000-0000-4000-8000-000000000031',1,'Synthetic history stays');

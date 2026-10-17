-- A recap is a document under an interview: the company, role and interview date
-- belong to the process, so the recap no longer keeps its own copy of them.
-- Binding is enforced when a recap is created; the column stays nullable so
-- legacy recaps recorded before this rule are not destroyed by the migration.
ALTER TABLE interview_recaps DROP COLUMN company;
ALTER TABLE interview_recaps DROP COLUMN target_role;
ALTER TABLE interview_recaps DROP COLUMN occurred_at;

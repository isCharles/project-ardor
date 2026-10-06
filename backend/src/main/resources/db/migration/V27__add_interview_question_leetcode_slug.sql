-- Coding questions in mock interviews link to a LeetCode Hot 100 problem.
-- Older sessions keep NULL and behave as before.
ALTER TABLE interview_questions ADD COLUMN leetcode_slug VARCHAR(100);

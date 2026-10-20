-- Compatibility source_type/source_id remain untouched for old clients and rollback.
ALTER TABLE learning_plans ADD COLUMN origin_kind VARCHAR(32);
ALTER TABLE learning_plans ADD COLUMN origin_resource_id UUID;
ALTER TABLE learning_plans ADD COLUMN origin_record_id UUID;
ALTER TABLE learning_plans ADD COLUMN target_interview_id UUID;
ALTER TABLE learning_plans ADD COLUMN target_round_id UUID;

UPDATE learning_plans SET origin_kind=source_type,
    origin_resource_id=CASE WHEN source_type IN ('RESUME','KNOWLEDGE') THEN source_id ELSE NULL END
WHERE source_type <> 'RECAP';

UPDATE learning_plans p SET origin_kind='INTERVIEW_RECORD',origin_resource_id=p.source_id,origin_record_id=p.source_id
WHERE p.source_type='RECAP'
  AND EXISTS(SELECT 1 FROM interview_recaps r WHERE r.id=p.source_id AND r.user_id=p.user_id)
  AND NOT EXISTS(SELECT 1 FROM interview_recap_questions q WHERE q.id=p.source_id AND q.user_id=p.user_id);
UPDATE learning_plans p SET origin_kind='INTERVIEW_QUESTION',origin_resource_id=p.source_id,origin_record_id=q.recap_id
FROM interview_recap_questions q JOIN interview_recaps r ON r.id=q.recap_id AND r.user_id=q.user_id
WHERE p.source_type='RECAP' AND q.id=p.source_id AND q.user_id=p.user_id
  AND NOT EXISTS(SELECT 1 FROM interview_recaps collision WHERE collision.id=p.source_id AND collision.user_id=p.user_id);
UPDATE learning_plans SET origin_kind='LEGACY_RECAP',origin_resource_id=source_id
WHERE source_type='RECAP' AND origin_kind IS NULL;

ALTER TABLE learning_plans ADD CONSTRAINT ck_learning_origin_kind CHECK (origin_kind IS NULL OR origin_kind IN
    ('MANUAL','AGENT','RESUME','KNOWLEDGE','INTERVIEW_RECORD','INTERVIEW_QUESTION','LEGACY_RECAP'));
ALTER TABLE learning_plans ADD CONSTRAINT fk_learning_target_interview
    FOREIGN KEY(target_interview_id,user_id) REFERENCES job_opportunities(id,user_id)
    ON DELETE SET NULL(target_interview_id);
ALTER TABLE learning_plans ADD CONSTRAINT fk_learning_target_round
    FOREIGN KEY(target_round_id,target_interview_id,user_id) REFERENCES job_opportunity_rounds(id,opportunity_id,user_id)
    ON DELETE SET NULL(target_round_id);
CREATE INDEX ix_learning_user_origin ON learning_plans(user_id,origin_kind,origin_resource_id);
CREATE INDEX ix_learning_user_target ON learning_plans(user_id,target_interview_id,target_round_id);
-- No source FK: deleting source material must not delete acquired learning or its provenance identity.
-- No inferred target: a historical source interview is not necessarily the interview being prepared for.

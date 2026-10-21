-- Structured calendar advice: Ardor must return a short headline plus at most
-- three one-line actions per horizon. Legacy text columns stay readable and are
-- normalised on read so existing saved advice is not lost.
ALTER TABLE calendar_advice ADD COLUMN payload JSONB;

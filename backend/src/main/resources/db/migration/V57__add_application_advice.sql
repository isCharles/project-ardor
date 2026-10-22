-- Ardor's application-pace advice belongs to the existing rhythm settings row
-- rather than a calendar note. It is shown at the bottom of the interviews page.
ALTER TABLE application_rhythm_settings ADD COLUMN advice TEXT;

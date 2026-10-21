-- The calendar's advice card is user-owned state written by Ardor, not a
-- reconstructed client-side guess. One row per user; empty sections are null.
CREATE TABLE calendar_advice (
    user_id UUID PRIMARY KEY,
    days TEXT,
    weeks TEXT,
    months TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_calendar_advice_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

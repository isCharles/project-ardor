CREATE TABLE application_rhythm_settings (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    weekly_goal INTEGER NOT NULL DEFAULT 0 CHECK (weekly_goal BETWEEN 0 AND 500),
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_time TIME NOT NULL DEFAULT TIME '19:00',
    weekdays_only BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_dismissed_on DATE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_daily_counts (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    application_date DATE NOT NULL,
    count INTEGER NOT NULL CHECK (count BETWEEN 0 AND 500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, application_date)
);

CREATE INDEX ix_application_daily_counts_user_date
    ON application_daily_counts (user_id, application_date DESC);

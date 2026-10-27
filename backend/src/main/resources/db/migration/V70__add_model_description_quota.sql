INSERT INTO usage_policies (
    feature, free_monthly_limit, member_monthly_limit, plus_monthly_limit, pro_monthly_limit,
    free_weekly_limit, plus_weekly_limit, pro_weekly_limit, user_minute_limit, global_minute_limit
) VALUES (
    'MODEL_DESCRIPTION', 120, 1000, 1000, 4000,
    30, 250, 1000, 6, 60
);

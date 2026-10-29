package com.projectardor.jobsearch.applicationactivity.service;

import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.platform.identity.profile.service.ProfileService;

@Service
public class ApplicationRhythmService {
    private final JdbcTemplate jdbc;
    private final ProfileService profiles;
    private final Clock clock;

    @Autowired
    public ApplicationRhythmService(JdbcTemplate jdbc, ProfileService profiles) {
        this(jdbc, profiles, Clock.systemUTC());
    }

    ApplicationRhythmService(JdbcTemplate jdbc, ProfileService profiles, Clock clock) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplicationRhythmView get(UUID userId) {
        ZonedDateTime now = clock.instant().atZone(zone(userId));
        LocalDate today = now.toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Settings settings = jdbc.query("""
                SELECT weekly_resume_goal, weekly_greeting_goal, reminder_enabled, reminder_time,
                       weekdays_only, reminder_dismissed_on, advice
                FROM application_rhythm_settings WHERE user_id = ?
                """, (rs, row) -> new Settings(rs.getInt(1), rs.getObject(2, Integer.class), rs.getBoolean(3),
                rs.getTime(4).toLocalTime(), rs.getBoolean(5),
                rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getString(7)), userId)
                .stream().findFirst()
                .orElse(new Settings(0, null, false, LocalTime.of(19, 0), true, null, null));

        Map<LocalDate, ApplicationRhythmView.DayCount> recorded = new HashMap<>();
        jdbc.query("""
                SELECT application_date, greeting_count, resume_count FROM application_daily_counts
                WHERE user_id = ? AND application_date >= ? AND application_date < ?
                """, rs -> {
                    LocalDate date = rs.getDate(1).toLocalDate();
                    recorded.put(date, new ApplicationRhythmView.DayCount(date,
                            rs.getObject(2, Integer.class), rs.getObject(3, Integer.class)));
                }, userId, Date.valueOf(weekStart), Date.valueOf(weekStart.plusDays(7)));

        List<ApplicationRhythmView.DayCount> days = new ArrayList<>(7);
        int weeklyGreeting = 0;
        int weeklyResume = 0;
        int greetingRecordedDays = 0;
        int resumeRecordedDays = 0;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            ApplicationRhythmView.DayCount day = recorded.getOrDefault(date,
                    new ApplicationRhythmView.DayCount(date, null, null));
            days.add(day);
            if (day.greetingCount() != null) {
                weeklyGreeting += day.greetingCount();
                greetingRecordedDays++;
            }
            if (day.resumeCount() != null) {
                weeklyResume += day.resumeCount();
                resumeRecordedDays++;
            }
        }
        ApplicationRhythmView.DayCount todayRow = recorded.get(today);
        Integer todayGreeting = todayRow == null ? null : todayRow.greetingCount();
        Integer todayResume = todayRow == null ? null : todayRow.resumeCount();
        return new ApplicationRhythmView(today, weekStart, todayGreeting, todayResume,
                weeklyGreeting, weeklyResume, greetingRecordedDays, resumeRecordedDays,
                settings.weeklyResumeGoal(), settings.weeklyGreetingGoal(),
                settings.reminderEnabled(), settings.reminderTime(), settings.weekdaysOnly(),
                reminderDue(settings, now, weeklyGreeting, weeklyResume,
                        todayGreeting != null, todayResume != null),
                List.copyOf(days), settings.advice());
    }

    @Transactional
    public ApplicationRhythmView configure(UUID userId, int weeklyResumeGoal, Integer weeklyGreetingGoal,
            boolean reminderEnabled, LocalTime reminderTime, boolean weekdaysOnly) {
        if (weeklyResumeGoal < 0 || weeklyResumeGoal > 500) {
            throw new IllegalArgumentException("每周简历投递目标须在 0 到 500 之间");
        }
        if (weeklyGreetingGoal != null && (weeklyGreetingGoal < 0 || weeklyGreetingGoal > 500)) {
            throw new IllegalArgumentException("每周打招呼目标须在 0 到 500 之间");
        }
        if (reminderTime == null) throw new IllegalArgumentException("请设置提醒时间");
        jdbc.update("""
                INSERT INTO application_rhythm_settings
                    (user_id, weekly_resume_goal, weekly_greeting_goal, reminder_enabled, reminder_time, weekdays_only)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    weekly_resume_goal = EXCLUDED.weekly_resume_goal,
                    weekly_greeting_goal = EXCLUDED.weekly_greeting_goal,
                    reminder_enabled = EXCLUDED.reminder_enabled,
                    reminder_time = EXCLUDED.reminder_time,
                    weekdays_only = EXCLUDED.weekdays_only,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, weeklyResumeGoal,
                weeklyGreetingGoal == null || weeklyGreetingGoal == 0 ? null : weeklyGreetingGoal,
                reminderEnabled, Time.valueOf(reminderTime), weekdaysOnly);
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmView updateAdvice(UUID userId, String advice) {
        String clean = advice == null ? "" : advice.strip();
        if (clean.length() > 8000) throw new IllegalArgumentException("投递建议不能超过 8000 字");
        jdbc.update("""
                INSERT INTO application_rhythm_settings (user_id, advice)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    advice = EXCLUDED.advice,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, clean.isBlank() ? null : clean);
        return get(userId);
    }

    /**
     * Sets only the totals the caller supplied. A null field is "leave unrecorded/unchanged",
     * never zero, so a greeting-only edit cannot erase a resume total and vice versa.
     */
    @Transactional
    public ApplicationRhythmView record(UUID userId, LocalDate date, Integer greetingCount, Integer resumeCount) {
        validateDate(userId, date);
        if (greetingCount == null && resumeCount == null) {
            throw new IllegalArgumentException("请至少提供打招呼数或简历投递数");
        }
        validateCount(greetingCount, "每天的打招呼数");
        validateCount(resumeCount, "每天的简历投递数");
        jdbc.update("""
                INSERT INTO application_daily_counts (user_id, application_date, greeting_count, resume_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, application_date) DO UPDATE SET
                    greeting_count = COALESCE(EXCLUDED.greeting_count, application_daily_counts.greeting_count),
                    resume_count = COALESCE(EXCLUDED.resume_count, application_daily_counts.resume_count),
                    updated_at = CURRENT_TIMESTAMP
                """, userId, Date.valueOf(date), greetingCount, resumeCount);
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmView incrementToday(UUID userId, ApplicationMetric metric) {
        LocalDate today = LocalDate.now(clock.withZone(zone(userId)));
        List<Integer> changed = jdbc.query(switch (metric) {
            case GREETING -> """
                    INSERT INTO application_daily_counts (user_id, application_date, greeting_count)
                    VALUES (?, ?, 1)
                    ON CONFLICT (user_id, application_date) DO UPDATE SET
                        greeting_count = COALESCE(application_daily_counts.greeting_count, 0) + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE COALESCE(application_daily_counts.greeting_count, 0) < 500
                    RETURNING greeting_count
                    """;
            case RESUME -> """
                    INSERT INTO application_daily_counts (user_id, application_date, resume_count)
                    VALUES (?, ?, 1)
                    ON CONFLICT (user_id, application_date) DO UPDATE SET
                        resume_count = COALESCE(application_daily_counts.resume_count, 0) + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE COALESCE(application_daily_counts.resume_count, 0) < 500
                    RETURNING resume_count
                    """;
        }, (rs, row) -> rs.getInt(1), userId, Date.valueOf(today));
        if (changed.isEmpty()) {
            throw new IllegalArgumentException(metric == ApplicationMetric.GREETING
                    ? "今天的打招呼数已达到上限 500" : "今天的简历投递数已达到上限 500");
        }
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmView dismissToday(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(zone(userId)));
        jdbc.update("""
                INSERT INTO application_rhythm_settings (user_id, reminder_dismissed_on)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    reminder_dismissed_on = EXCLUDED.reminder_dismissed_on,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, Date.valueOf(today));
        return get(userId);
    }

    static boolean reminderDue(Settings settings, ZonedDateTime now, int weeklyGreetingCount,
            int weeklyResumeCount, boolean greetingRecordedToday, boolean resumeRecordedToday) {
        if (!settings.reminderEnabled() || now.toLocalDate().equals(settings.reminderDismissedOn())) return false;
        boolean resumeGoalActive = settings.weeklyResumeGoal() > 0;
        boolean greetingGoalActive = settings.weeklyGreetingGoal() != null && settings.weeklyGreetingGoal() > 0;
        if (!resumeGoalActive && !greetingGoalActive) return false;
        if (settings.weekdaysOnly() && now.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) return false;
        if (now.toLocalTime().isBefore(settings.reminderTime())) return false;
        boolean resumePending = resumeGoalActive && !resumeRecordedToday
                && weeklyResumeCount < settings.weeklyResumeGoal();
        boolean greetingPending = greetingGoalActive && !greetingRecordedToday
                && weeklyGreetingCount < settings.weeklyGreetingGoal();
        return resumePending || greetingPending;
    }

    private void validateDate(UUID userId, LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(zone(userId)));
        if (date == null || date.isAfter(today) || date.isBefore(today.minusDays(90))) {
            throw new IllegalArgumentException("只能记录今天及过去 90 天内的投递数");
        }
    }

    private static void validateCount(Integer count, String label) {
        if (count != null && (count < 0 || count > 500)) {
            throw new IllegalArgumentException(label + "须在 0 到 500 之间");
        }
    }

    private ZoneId zone(UUID userId) {
        return ZoneId.of(profiles.view(userId).timezone());
    }

    record Settings(int weeklyResumeGoal, Integer weeklyGreetingGoal, boolean reminderEnabled,
            LocalTime reminderTime, boolean weekdaysOnly, LocalDate reminderDismissedOn, String advice) {}
}

package com.projectardor.applications.service;

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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.applications.web.ApplicationRhythmResponse;
import com.projectardor.profile.service.ProfileService;

@Service
public class ApplicationRhythmService {
    private final JdbcTemplate jdbc;
    private final ProfileService profiles;
    private final Clock clock;

    public ApplicationRhythmService(JdbcTemplate jdbc, ProfileService profiles) {
        this(jdbc, profiles, Clock.systemUTC());
    }

    ApplicationRhythmService(JdbcTemplate jdbc, ProfileService profiles, Clock clock) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplicationRhythmResponse get(UUID userId) {
        ZonedDateTime now = clock.instant().atZone(zone(userId));
        LocalDate today = now.toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Settings settings = jdbc.query("""
                SELECT weekly_goal, reminder_enabled, reminder_time, weekdays_only, reminder_dismissed_on
                FROM application_rhythm_settings WHERE user_id = ?
                """, (rs, row) -> new Settings(rs.getInt(1), rs.getBoolean(2), rs.getTime(3).toLocalTime(),
                rs.getBoolean(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate()), userId)
                .stream().findFirst().orElse(new Settings(0, false, LocalTime.of(19, 0), true, null));

        Map<LocalDate, Integer> counts = new HashMap<>();
        jdbc.query("""
                SELECT application_date, count FROM application_daily_counts
                WHERE user_id = ? AND application_date >= ? AND application_date < ?
                """, rs -> { counts.put(rs.getDate(1).toLocalDate(), rs.getInt(2)); },
                userId, Date.valueOf(weekStart), Date.valueOf(weekStart.plusDays(7)));
        List<ApplicationRhythmResponse.DayCount> days = new ArrayList<>(7);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = weekStart.plusDays(offset);
            days.add(new ApplicationRhythmResponse.DayCount(day, counts.getOrDefault(day, 0)));
        }
        int weeklyCount = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new ApplicationRhythmResponse(today, weekStart, counts.getOrDefault(today, 0), weeklyCount,
                settings.weeklyGoal(), settings.reminderEnabled(), settings.reminderTime(),
                settings.weekdaysOnly(), reminderDue(settings, now, weeklyCount, counts.containsKey(today)),
                List.copyOf(days));
    }

    @Transactional
    public ApplicationRhythmResponse configure(UUID userId, int weeklyGoal, boolean reminderEnabled,
            LocalTime reminderTime, boolean weekdaysOnly) {
        if (weeklyGoal < 0 || weeklyGoal > 500) throw new IllegalArgumentException("每周目标须在 0 到 500 之间");
        if (reminderTime == null) throw new IllegalArgumentException("请设置提醒时间");
        jdbc.update("""
                INSERT INTO application_rhythm_settings
                    (user_id, weekly_goal, reminder_enabled, reminder_time, weekdays_only)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    weekly_goal = EXCLUDED.weekly_goal,
                    reminder_enabled = EXCLUDED.reminder_enabled,
                    reminder_time = EXCLUDED.reminder_time,
                    weekdays_only = EXCLUDED.weekdays_only,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, weeklyGoal, reminderEnabled, Time.valueOf(reminderTime), weekdaysOnly);
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmResponse record(UUID userId, LocalDate date, int count) {
        validateDate(userId, date);
        if (count < 0 || count > 500) throw new IllegalArgumentException("每天的投递数须在 0 到 500 之间");
        jdbc.update("""
                INSERT INTO application_daily_counts (user_id, application_date, count)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, application_date) DO UPDATE SET
                    count = EXCLUDED.count, updated_at = CURRENT_TIMESTAMP
                """, userId, Date.valueOf(date), count);
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmResponse incrementToday(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(zone(userId)));
        List<Integer> changed = jdbc.query("""
                INSERT INTO application_daily_counts (user_id, application_date, count)
                VALUES (?, ?, 1)
                ON CONFLICT (user_id, application_date) DO UPDATE SET
                    count = application_daily_counts.count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE application_daily_counts.count < 500
                RETURNING count
                """, (rs, row) -> rs.getInt(1), userId, Date.valueOf(today));
        if (changed.isEmpty()) throw new IllegalArgumentException("今天的投递数已达到上限 500");
        return get(userId);
    }

    @Transactional
    public ApplicationRhythmResponse dismissToday(UUID userId) {
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

    static boolean reminderDue(Settings settings, ZonedDateTime now, int weeklyCount, boolean recordedToday) {
        if (!settings.reminderEnabled() || settings.weeklyGoal() == 0
                || weeklyCount >= settings.weeklyGoal() || recordedToday
                || now.toLocalDate().equals(settings.reminderDismissedOn())) return false;
        if (settings.weekdaysOnly() && now.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) return false;
        return !now.toLocalTime().isBefore(settings.reminderTime());
    }

    private void validateDate(UUID userId, LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(zone(userId)));
        if (date == null || date.isAfter(today) || date.isBefore(today.minusDays(90))) {
            throw new IllegalArgumentException("只能记录今天及过去 90 天内的投递数");
        }
    }

    private ZoneId zone(UUID userId) {
        return ZoneId.of(profiles.get(userId).getTimezone());
    }

    record Settings(int weeklyGoal, boolean reminderEnabled, LocalTime reminderTime,
            boolean weekdaysOnly, LocalDate reminderDismissedOn) {}
}

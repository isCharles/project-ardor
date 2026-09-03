package com.projectardor.calendar.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * The rule behind a repeating arrangement — 每周四组会, 每天读书, 每月 15 号复盘.
 *
 * <p>The rule itself never appears on the calendar. It is expanded into real
 * {@link CalendarTask} rows ahead of time, so an occurrence can be completed,
 * rescheduled or deleted on its own without the series knowing or caring.
 */
@Entity
@Table(name = "task_series")
public class TaskSeries {

    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 240) private String title;
    @Column(columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CalendarTaskPriority priority;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private CalendarTaskSource source;
    @Column(nullable = false, length = 80) private String timezone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private RecurrenceFrequency frequency;
    @Column(name = "interval_value", nullable = false) private int intervalValue;
    /** Weekly rules only: "MON,THU". Empty means "the weekday the series starts on". */
    @Column(name = "by_weekdays", length = 64) private String byWeekdays;
    /** Monthly rules only. Null means "the day of month the series starts on". */
    @Column(name = "by_month_day") private Integer byMonthDay;
    @Column(name = "time_of_day", nullable = false) private LocalTime timeOfDay;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "until_date") private LocalDate untilDate;
    @Column(name = "occurrence_limit") private Integer occurrenceLimit;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private TaskSeriesStatus status;
    @Column(name = "materialized_through") private LocalDate materializedThrough;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TaskSeries() {}

    public static TaskSeries create(
            UUID userId,
            String title,
            String description,
            CalendarTaskPriority priority,
            CalendarTaskSource source,
            ZoneId timezone,
            RecurrenceFrequency frequency,
            int intervalValue,
            Set<DayOfWeek> weekdays,
            Integer monthDay,
            LocalTime timeOfDay,
            LocalDate startDate,
            LocalDate untilDate,
            Integer occurrenceLimit) {
        TaskSeries series = new TaskSeries();
        series.id = UUID.randomUUID();
        series.userId = userId;
        series.title = title;
        series.description = description;
        series.priority = priority == null ? CalendarTaskPriority.MEDIUM : priority;
        series.source = source == null ? CalendarTaskSource.MANUAL : source;
        series.timezone = timezone.getId();
        series.frequency = frequency;
        series.intervalValue = intervalValue;
        series.byWeekdays = weekdays == null || weekdays.isEmpty() ? null : format(weekdays);
        series.byMonthDay = monthDay;
        series.timeOfDay = timeOfDay;
        series.startDate = startDate;
        series.untilDate = untilDate;
        series.occurrenceLimit = occurrenceLimit;
        series.status = TaskSeriesStatus.ACTIVE;
        return series;
    }

    /* ---------------------------------------------------------------
       Expansion. Pure date arithmetic on the rule — no persistence, no
       clock, so it can be reasoned about and tested directly.
       --------------------------------------------------------------- */

    /**
     * Every date this series falls on from its start through {@code horizon}
     * inclusive, already limited by its end date and occurrence count.
     */
    public List<LocalDate> occurrencesThrough(LocalDate horizon) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate end = untilDate != null && untilDate.isBefore(horizon) ? untilDate : horizon;
        if (end == null || end.isBefore(startDate)) return dates;
        int cap = cap();

        switch (frequency) {
            case DAILY -> {
                for (LocalDate date = startDate; !date.isAfter(end) && dates.size() < cap;
                        date = date.plusDays(intervalValue)) {
                    dates.add(date);
                }
            }
            case WEEKLY -> {
                Set<DayOfWeek> days = weekdays();
                // Intervals count from the week the series starts in, so
                // "每两周的周一" stays on the same fortnight forever.
                LocalDate week = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                while (!week.isAfter(end) && dates.size() < cap) {
                    for (int offset = 0; offset < 7 && dates.size() < cap; offset++) {
                        LocalDate date = week.plusDays(offset);
                        if (!days.contains(date.getDayOfWeek())) continue;
                        if (date.isBefore(startDate) || date.isAfter(end)) continue;
                        dates.add(date);
                    }
                    week = week.plusWeeks(intervalValue);
                }
            }
            case MONTHLY -> {
                int day = byMonthDay == null ? startDate.getDayOfMonth() : byMonthDay;
                YearMonth month = YearMonth.from(startDate);
                while (dates.size() < cap && !month.atDay(1).isAfter(end)) {
                    // A month too short for the chosen day is skipped rather
                    // than pulled back to its last day: 31 号 means 31 号.
                    if (day <= month.lengthOfMonth()) {
                        LocalDate date = month.atDay(day);
                        if (date.isAfter(end)) break;
                        if (!date.isBefore(startDate)) dates.add(date);
                    }
                    month = month.plusMonths(intervalValue);
                }
            }
        }
        return dates;
    }

    /** True once the rule can produce nothing beyond {@code horizon}. */
    public boolean isExhaustedThrough(LocalDate horizon) {
        if (untilDate != null && !untilDate.isAfter(horizon)) return true;
        return occurrenceLimit != null && occurrencesThrough(horizon).size() >= occurrenceLimit;
    }

    /** The instant an occurrence on {@code date} is due, in the series' own zone. */
    public Instant dueAtOn(LocalDate date) {
        return ZonedDateTime.of(date, timeOfDay, ZoneId.of(timezone)).toInstant();
    }

    public Set<DayOfWeek> weekdays() {
        if (byWeekdays == null || byWeekdays.isBlank()) return EnumSet.of(startDate.getDayOfWeek());
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : byWeekdays.split(",")) {
            if (!token.isBlank()) days.add(DayOfWeek.valueOf(token.strip().toUpperCase(Locale.ROOT)));
        }
        return days.isEmpty() ? EnumSet.of(startDate.getDayOfWeek()) : days;
    }

    /** One line a person can check against what they asked for: 每周四 19:00. */
    public String summary() {
        String time = timeOfDay.toString();
        String every = intervalValue == 1 ? "每" : "每 " + intervalValue + " ";
        String cadence = switch (frequency) {
            case DAILY -> intervalValue == 1 ? "每天" : every + "天";
            case WEEKLY -> {
                String days = weekdays().stream()
                        .sorted()
                        .map(TaskSeries::weekdayLabel)
                        .reduce((left, right) -> left + "、" + right)
                        .orElse("");
                // weekdayLabel already carries 周, so the cadence prefix must not repeat it.
                yield (intervalValue == 1 ? "每" : every + "周的") + days;
            }
            case MONTHLY -> {
                int day = byMonthDay == null ? startDate.getDayOfMonth() : byMonthDay;
                yield (intervalValue == 1 ? "每月" : every + "月的") + day + " 日";
            }
        };
        String window = untilDate != null
                ? "，至 " + untilDate
                : occurrenceLimit != null ? "，共 " + occurrenceLimit + " 次" : "";
        return cadence + " " + time + window;
    }

    /* --------------------------------------------------------------- */

    public void markMaterializedThrough(LocalDate horizon) {
        if (materializedThrough == null || materializedThrough.isBefore(horizon)) {
            materializedThrough = horizon;
        }
    }

    public void end() { this.status = TaskSeriesStatus.ENDED; }

    public void cancel() { this.status = TaskSeriesStatus.CANCELLED; }

    private int cap() {
        return occurrenceLimit == null ? Integer.MAX_VALUE : occurrenceLimit;
    }

    private static String format(Set<DayOfWeek> weekdays) {
        Set<String> ordered = new LinkedHashSet<>();
        Arrays.stream(DayOfWeek.values()).filter(weekdays::contains).forEach(day -> ordered.add(day.name()));
        return String.join(",", ordered);
    }

    private static String weekdayLabel(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public CalendarTaskPriority getPriority() { return priority; }
    public CalendarTaskSource getSource() { return source; }
    public String getTimezone() { return timezone; }
    public RecurrenceFrequency getFrequency() { return frequency; }
    public int getIntervalValue() { return intervalValue; }
    public Integer getByMonthDay() { return byMonthDay; }
    public LocalTime getTimeOfDay() { return timeOfDay; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getUntilDate() { return untilDate; }
    public Integer getOccurrenceLimit() { return occurrenceLimit; }
    public TaskSeriesStatus getStatus() { return status; }
    public LocalDate getMaterializedThrough() { return materializedThrough; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

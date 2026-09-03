package com.projectardor.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TaskSeriesTests {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private TaskSeries weekly(Set<DayOfWeek> days, int interval, LocalDate start, LocalDate until, Integer limit) {
        return TaskSeries.create(UUID.randomUUID(), "组会", null, CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT, SHANGHAI, RecurrenceFrequency.WEEKLY, interval, days, null,
                LocalTime.of(19, 0), start, until, limit);
    }

    @Test
    void weeklyRuleLandsOnEveryRequestedWeekday() {
        // 2026-09-03 is a Thursday.
        TaskSeries series = weekly(EnumSet.of(DayOfWeek.THURSDAY), 1,
                LocalDate.of(2026, 9, 3), null, null);

        List<LocalDate> dates = series.occurrencesThrough(LocalDate.of(2026, 10, 1));

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 10, 1));
    }

    @Test
    void weeklyRuleSkipsWeekdaysBeforeTheStartDate() {
        // Starting on Thursday, the Monday of that same week must not appear.
        TaskSeries series = weekly(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 1,
                LocalDate.of(2026, 9, 3), null, null);

        assertThat(series.occurrencesThrough(LocalDate.of(2026, 9, 14))).containsExactly(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 14));
    }

    @Test
    void fortnightlyRuleStaysOnTheStartingWeek() {
        TaskSeries series = weekly(EnumSet.of(DayOfWeek.THURSDAY), 2,
                LocalDate.of(2026, 9, 3), null, null);

        assertThat(series.occurrencesThrough(LocalDate.of(2026, 10, 15))).containsExactly(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15));
    }

    @Test
    void occurrenceLimitAndEndDateBothStopTheSeries() {
        TaskSeries counted = weekly(EnumSet.of(DayOfWeek.THURSDAY), 1,
                LocalDate.of(2026, 9, 3), null, 3);
        TaskSeries bounded = weekly(EnumSet.of(DayOfWeek.THURSDAY), 1,
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 12), null);

        assertThat(counted.occurrencesThrough(LocalDate.of(2026, 12, 31))).hasSize(3);
        assertThat(bounded.occurrencesThrough(LocalDate.of(2026, 12, 31)))
                .containsExactly(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 10));
        assertThat(counted.isExhaustedThrough(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(bounded.isExhaustedThrough(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void dailyRuleHonoursItsInterval() {
        TaskSeries series = TaskSeries.create(UUID.randomUUID(), "读书", null, CalendarTaskPriority.LOW,
                CalendarTaskSource.AGENT, SHANGHAI, RecurrenceFrequency.DAILY, 3, Set.of(), null,
                LocalTime.of(7, 30), LocalDate.of(2026, 9, 3), null, null);

        assertThat(series.occurrencesThrough(LocalDate.of(2026, 9, 12))).containsExactly(
                LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 6),
                LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 12));
    }

    @Test
    void monthlyRuleSkipsMonthsWithoutThatDay() {
        TaskSeries series = TaskSeries.create(UUID.randomUUID(), "复盘", null, CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT, SHANGHAI, RecurrenceFrequency.MONTHLY, 1, Set.of(), 31,
                LocalTime.of(21, 0), LocalDate.of(2026, 1, 31), null, null);

        // February and April have no 31st; those months produce nothing at all.
        assertThat(series.occurrencesThrough(LocalDate.of(2026, 5, 31))).containsExactly(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31));
    }

    @Test
    void dueInstantUsesTheSeriesOwnZone() {
        TaskSeries series = weekly(EnumSet.of(DayOfWeek.THURSDAY), 1,
                LocalDate.of(2026, 9, 3), null, null);

        assertThat(series.dueAtOn(LocalDate.of(2026, 9, 3)))
                .isEqualTo("2026-09-03T11:00:00Z"); // 19:00 +08:00
    }

    @Test
    void summaryReadsBackTheRule() {
        assertThat(weekly(EnumSet.of(DayOfWeek.THURSDAY), 1, LocalDate.of(2026, 9, 3), null, null).summary())
                .isEqualTo("每周四 19:00");
        assertThat(weekly(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), 2,
                LocalDate.of(2026, 9, 3), null, 10).summary())
                .isEqualTo("每 2 周的周一、周四 19:00，共 10 次");
    }
}

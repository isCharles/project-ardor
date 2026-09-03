package com.projectardor.calendar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CalendarTaskTests {

    @Test
    void editingAnOccurrenceMarksItForPreservationWhenItsSeriesIsCancelled() {
        TaskSeries series = TaskSeries.create(
                UUID.randomUUID(), "组会", "项目同步", CalendarTaskPriority.MEDIUM,
                CalendarTaskSource.AGENT, ZoneId.of("Asia/Shanghai"), RecurrenceFrequency.WEEKLY,
                1, Set.of(), null, LocalTime.of(19, 0), LocalDate.of(2026, 9, 3), null, null);
        CalendarTask task = CalendarTask.createOccurrence(series, LocalDate.of(2026, 9, 10));

        assertThat(task.isUserModified()).isFalse();

        task.update("改期组会", "与产品同步", series.dueAtOn(LocalDate.of(2026, 9, 11)),
                CalendarTaskPriority.HIGH, CalendarTaskStatus.TODO);

        assertThat(task.isUserModified()).isTrue();
    }
}

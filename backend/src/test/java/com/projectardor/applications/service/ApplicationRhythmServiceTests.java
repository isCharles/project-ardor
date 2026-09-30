package com.projectardor.applications.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class ApplicationRhythmServiceTests {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test
    void reminderStartsOnlyAfterConfiguredTimeAndBeforeWeeklyGoalIsMet() {
        var settings = new ApplicationRhythmService.Settings(20, true, LocalTime.of(19, 0), true, null);
        var monday = ZonedDateTime.of(2026, 9, 28, 19, 5, 0, 0, zone);

        assertThat(ApplicationRhythmService.reminderDue(settings, monday.minusMinutes(6), 5, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 5, false)).isTrue();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 20, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 5, true)).isFalse();
    }

    @Test
    void reminderRespectsWeekdaysPauseAndDisabledSettings() {
        var saturday = ZonedDateTime.of(2026, 10, 3, 20, 0, 0, 0, zone);
        var weekdays = new ApplicationRhythmService.Settings(20, true, LocalTime.of(19, 0), true, null);
        assertThat(ApplicationRhythmService.reminderDue(weekdays, saturday, 5, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, true, LocalTime.of(19, 0), false, null),
                saturday, 5, false)).isTrue();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, true, LocalTime.of(19, 0), false,
                        LocalDate.of(2026, 10, 3)), saturday, 5, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, false, LocalTime.of(19, 0), false, null),
                saturday, 5, false)).isFalse();
    }
}

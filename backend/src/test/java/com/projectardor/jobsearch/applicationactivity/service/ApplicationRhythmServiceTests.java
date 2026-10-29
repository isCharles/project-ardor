package com.projectardor.jobsearch.applicationactivity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.projectardor.platform.identity.profile.service.ProfileService;

class ApplicationRhythmServiceTests {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test
    void springCanCreateServiceWithItsInjectableConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(ProfileService.class, () -> mock(ProfileService.class));
            context.registerBean(ApplicationRhythmService.class);
            context.refresh();
            assertThat(context.getBean(ApplicationRhythmService.class)).isNotNull();
        }
    }

    @Test
    void reminderStartsOnlyAfterConfiguredTimeAndBeforeWeeklyGoalIsMet() {
        var settings = new ApplicationRhythmService.Settings(20, null, true, LocalTime.of(19, 0), true, null, null);
        var monday = ZonedDateTime.of(2026, 9, 28, 19, 5, 0, 0, zone);

        assertThat(ApplicationRhythmService.reminderDue(settings, monday.minusMinutes(6), 0, 5, false, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 5, false, false)).isTrue();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 20, false, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 5, false, true)).isFalse();
    }

    @Test
    void reminderRespectsWeekdaysPauseAndDisabledSettings() {
        var saturday = ZonedDateTime.of(2026, 10, 3, 20, 0, 0, 0, zone);
        var weekdays = new ApplicationRhythmService.Settings(20, null, true, LocalTime.of(19, 0), true, null, null);
        assertThat(ApplicationRhythmService.reminderDue(weekdays, saturday, 0, 5, false, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, null, true, LocalTime.of(19, 0), false, null, null),
                saturday, 0, 5, false, false)).isTrue();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, null, true, LocalTime.of(19, 0), false,
                        LocalDate.of(2026, 10, 3), null), saturday, 0, 5, false, false)).isFalse();
        assertThat(ApplicationRhythmService.reminderDue(
                new ApplicationRhythmService.Settings(20, null, false, LocalTime.of(19, 0), false, null, null),
                saturday, 0, 5, false, false)).isFalse();
    }

    @Test
    void aGreetingGoalNeverUsesTheResumeCountToDecideWhetherItIsMet() {
        var settings = new ApplicationRhythmService.Settings(0, 10, true, LocalTime.of(19, 0), false, null, null);
        var monday = ZonedDateTime.of(2026, 10, 26, 19, 5, 0, 0, zone);

        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 9, 0, false, true))
                .as("the resume record cannot satisfy the greeting goal").isTrue();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 10, 0, true, false))
                .as("the greeting goal is met and today's greeting is recorded").isFalse();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 0, false, false))
                .as("only the configured greeting goal matters").isTrue();
    }

    @Test
    void aResumeGoalNeverUsesTheGreetingCountToDecideWhetherItIsMet() {
        var settings = new ApplicationRhythmService.Settings(10, null, true, LocalTime.of(19, 0), false, null, null);
        var monday = ZonedDateTime.of(2026, 10, 26, 19, 5, 0, 0, zone);

        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 50, 9, true, false))
                .as("89 greetings do not count as resume sends").isTrue();
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 10, true, true)).isFalse();
    }

    @Test
    void remindersStayOffUntilAtLeastOneGoalIsConfigured() {
        var monday = ZonedDateTime.of(2026, 10, 26, 19, 5, 0, 0, zone);
        var settings = new ApplicationRhythmService.Settings(0, null, true, LocalTime.of(19, 0), false, null, null);
        assertThat(ApplicationRhythmService.reminderDue(settings, monday, 0, 0, false, false)).isFalse();
    }
}

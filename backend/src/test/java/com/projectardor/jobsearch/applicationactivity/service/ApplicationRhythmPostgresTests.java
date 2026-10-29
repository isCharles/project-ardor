package com.projectardor.jobsearch.applicationactivity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.identity.profile.service.ProfileView;

/**
 * The old count column carried greetings. The migration must keep those numbers under that
 * meaning and leave historical resume sends unrecorded instead of turning them into zero.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class ApplicationRhythmPostgresTests {
    private static final String URL = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
            "jdbc:postgresql://127.0.0.1:5432/ardor");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test void migrationPreservesLegacyGreetingsAndMarksResumeCountsUnrecorded() {
        withSchema("ardor_rhythm_migration", jdbc -> {
            // Migrate only up to the pre-change schema, then write rows exactly as the old app did.
            Flyway.configure().dataSource(jdbc.getDataSource()).schemas(jdbc.schema()).defaultSchema(jdbc.schema())
                    .target("90").load().migrate();
            UUID user = UUID.randomUUID();
            jdbc.template().update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                    user, user + "@ardor.invalid", "synthetic-only");
            jdbc.template().update("""
                    INSERT INTO application_daily_counts (user_id, application_date, count)
                    VALUES (?, ?, 89)
                    """, user, java.sql.Date.valueOf(TODAY));
            jdbc.template().update("""
                    INSERT INTO application_rhythm_settings (user_id, weekly_goal, reminder_enabled)
                    VALUES (?, 20, TRUE)
                    """, user);

            Flyway.configure().dataSource(jdbc.getDataSource()).schemas(jdbc.schema()).defaultSchema(jdbc.schema())
                    .load().migrate();

            var day = jdbc.template().queryForMap("""
                    SELECT greeting_count, resume_count FROM application_daily_counts
                    WHERE user_id = ? AND application_date = ?
                    """, user, java.sql.Date.valueOf(TODAY));
            assertThat(day.get("greeting_count")).isEqualTo(89);
            assertThat(day.get("resume_count")).as("legacy resume sends are unrecorded, not zero").isNull();
            var settings = jdbc.template().queryForMap("""
                    SELECT weekly_resume_goal, weekly_greeting_goal FROM application_rhythm_settings
                    WHERE user_id = ?
                    """, user);
            assertThat(settings.get("weekly_resume_goal")).as("the old goal measured greetings").isEqualTo(0);
            assertThat(settings.get("weekly_greeting_goal")).isEqualTo(20);
            assertThat(jdbc.template().queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'application_daily_counts'
                    """, String.class, jdbc.schema())).doesNotContain("count");
        });
    }

    @Test void serviceReadsAndWritesTheTwoDailyTotalsIndependently() {
        withSchema("ardor_rhythm_service", jdbc -> {
            Flyway.configure().dataSource(jdbc.getDataSource()).schemas(jdbc.schema()).defaultSchema(jdbc.schema())
                    .load().migrate();
            UUID user = UUID.randomUUID();
            jdbc.template().update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                    user, user + "@ardor.invalid", "synthetic-only");
            var profiles = mock(ProfileService.class);
            when(profiles.view(user)).thenReturn(new ProfileView("", "", List.of(), "UTC"));
            var service = new ApplicationRhythmService(jdbc.template(), profiles, Clock.fixed(NOW, ZoneOffset.UTC));

            service.record(user, TODAY, 89, null);
            var greetingsOnly = service.get(user);
            assertThat(greetingsOnly.todayGreetingCount()).isEqualTo(89);
            assertThat(greetingsOnly.todayResumeCount()).isNull();
            assertThat(greetingsOnly.weeklyGreetingCount()).isEqualTo(89);
            assertThat(greetingsOnly.weeklyResumeCount()).isZero();
            assertThat(greetingsOnly.weeklyGreetingRecordedDays()).isEqualTo(1);
            assertThat(greetingsOnly.weeklyResumeRecordedDays()).isZero();

            service.record(user, TODAY, null, 5);
            var both = service.get(user);
            assertThat(both.todayGreetingCount()).as("a resume-only edit must not erase greetings").isEqualTo(89);
            assertThat(both.todayResumeCount()).isEqualTo(5);
            assertThat(both.weeklyResumeCount()).isEqualTo(5);
            assertThat(both.weeklyResumeRecordedDays()).isEqualTo(1);

            service.incrementToday(user, ApplicationMetric.RESUME);
            service.incrementToday(user, ApplicationMetric.GREETING);
            var incremented = service.get(user);
            assertThat(incremented.todayGreetingCount()).isEqualTo(90);
            assertThat(incremented.todayResumeCount()).isEqualTo(6);

            LocalDate yesterday = TODAY.minusDays(1);
            service.record(user, yesterday, 3, null);
            var week = service.get(user);
            assertThat(week.weeklyGreetingCount()).isEqualTo(93);
            assertThat(week.weeklyResumeCount()).isEqualTo(6);
            assertThat(week.weeklyGreetingRecordedDays()).isEqualTo(2);
            assertThat(week.weeklyResumeRecordedDays()).isEqualTo(1);
            assertThat(week.days()).filteredOn(day -> day.date().equals(yesterday)).singleElement()
                    .satisfies(day -> {
                        assertThat(day.greetingCount()).isEqualTo(3);
                        assertThat(day.resumeCount()).isNull();
                    });
        });
    }

    private static void withSchema(String prefix, SchemaTest body) {
        var root = new JdbcTemplate(new DriverManagerDataSource(URL, "ardor", "acceptance-only"));
        String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(URL + (URL.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
            body.run(new ScopedJdbc(new JdbcTemplate(source), schema));
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private record ScopedJdbc(JdbcTemplate template, String schema) {
        javax.sql.DataSource getDataSource() {
            return template.getDataSource();
        }
    }

    @FunctionalInterface
    private interface SchemaTest {
        void run(ScopedJdbc jdbc);
    }
}

package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Period keys must agree with the reset instant the user is told about, and a week key must never be
 * reused by two different weeks the way a week-of-year number can across a year boundary.
 */
class QuotaPeriodsTests {

    @Test
    void monthKeyAndResetFollowTheUtcCalendarMonth() {
        Instant lateOctober = Instant.parse("2026-10-31T23:59:59Z");

        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.MONTH, lateOctober)).isEqualTo("2026-10");
        assertThat(QuotaPeriods.resetAt(QuotaPeriods.Kind.MONTH, lateOctober))
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.MONTH, Instant.parse("2026-11-01T00:00:00Z")))
                .isEqualTo("2026-11");
    }

    @Test
    void weekKeyIsTheMondayThatStartsThePeriod() {
        // 2026-11-01 is a Sunday: it still belongs to the week that began Monday 2026-10-26.
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.WEEK, Instant.parse("2026-11-01T12:00:00Z")))
                .isEqualTo("2026-10-26");
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.WEEK, Instant.parse("2026-11-02T00:00:00Z")))
                .isEqualTo("2026-11-02");
        assertThat(QuotaPeriods.resetAt(QuotaPeriods.Kind.WEEK, Instant.parse("2026-11-01T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void weekKeysAroundTheYearBoundaryStayDistinct() {
        // 2027-01-01 is a Friday, so it shares its ISO week number with 2026-12-28 but not its Monday.
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.WEEK, Instant.parse("2026-12-28T00:00:00Z")))
                .isEqualTo("2026-12-28");
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.WEEK, Instant.parse("2027-01-01T00:00:00Z")))
                .isEqualTo("2026-12-28");
        assertThat(QuotaPeriods.key(QuotaPeriods.Kind.WEEK, Instant.parse("2027-01-04T00:00:00Z")))
                .isEqualTo("2027-01-04");
    }

    @Test
    void periodTtlAlwaysOutlivesThePeriodItLabels() {
        Instant justBeforeReset = Instant.parse("2026-11-30T23:59:00Z");

        assertThat(QuotaPeriods.ttlSeconds(QuotaPeriods.Kind.MONTH, justBeforeReset))
                .isGreaterThan(java.time.Duration.between(justBeforeReset,
                        QuotaPeriods.resetAt(QuotaPeriods.Kind.MONTH, justBeforeReset)).getSeconds());
    }

    @Test
    void burstWindowsPointAtTheStartOfTheNextMinute() {
        assertThat(QuotaPeriods.nextMinute(Instant.parse("2026-11-01T10:20:59.500Z")))
                .isEqualTo(Instant.parse("2026-11-01T10:21:00Z"));
    }
}

package com.projectardor.platform.usage;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Business period keys. A month is the UTC calendar month and a week is the ISO week that starts
 * Monday 00:00 UTC; the key is that Monday, so a key can never be shared by two weeks the way a
 * week-of-year number can across a year boundary.
 */
public final class QuotaPeriods {
    public enum Kind { MONTH, WEEK }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private QuotaPeriods() {}

    public static String key(Kind kind, Instant now) {
        return kind == Kind.MONTH ? MONTH.format(now) : weekStart(now).toString();
    }

    public static Instant resetAt(Kind kind, Instant now) {
        return kind == Kind.MONTH
                ? monthStart(now).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : weekStart(now).plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Seconds to keep a period counter after it stops being readable, so clock skew cannot drop it early. */
    public static long ttlSeconds(Kind kind, Instant now) {
        return ChronoUnit.SECONDS.between(now, resetAt(kind, now)) + 86_400;
    }

    public static Instant nextMinute(Instant now) {
        return now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
    }

    static LocalDate weekStart(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.MONDAY);
    }

    static LocalDate monthStart(Instant now) {
        var date = now.atZone(ZoneOffset.UTC).toLocalDate();
        return LocalDate.of(date.getYear(), date.getMonthValue(), 1);
    }
}

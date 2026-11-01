package com.projectardor.platform.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable business-period quotas. One transaction decides the whole multi-dimensional answer, so a
 * request that runs into the weekly ceiling never leaves a monthly increment behind.
 *
 * <p>The period rows use an upsert-with-guard: {@code INSERT ... SELECT ... WHERE limit > 0 ON
 * CONFLICT DO UPDATE ... WHERE used < limit}. A row count of zero is the denial, and PostgreSQL
 * serialises the two concurrent writers on that single row, so several instances cannot both take
 * the last unit.</p>
 */
@Repository
public class QuotaCounterStore {
    /** Marks older than this can no longer collide with a live client retry. */
    static final long MARK_RETENTION_DAYS = 40;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public QuotaCounterStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        // Admission must not be folded into a caller's transaction: a later business failure must not
        // hand the quota unit back, because the provider may already have been called.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean seen(UUID userId, UsageFeature feature, UUID requestId) {
        if (requestId == null) return false;
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_quota_requests WHERE user_id = ? AND feature = ? AND request_id = ?
                """, Integer.class, userId, feature.name(), requestId);
        return found != null && found > 0;
    }

    /**
     * Charges one unit for the current month and week, or nothing at all.
     *
     * @param monthLimit ceiling for the calendar month; zero blocks the period entirely
     * @param weekLimit ceiling for the Monday-anchored week; zero blocks the period entirely
     */
    public QuotaDecision consume(UUID userId, UsageFeature feature, int monthLimit, int weekLimit,
            UUID requestId, Instant now) {
        return transactions.execute(status -> {
            if (requestId != null) {
                int marked = jdbc.update("""
                        INSERT INTO usage_quota_requests(user_id, feature, request_id)
                        VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                        """, userId, feature.name(), requestId);
                if (marked == 0) {
                    // Another instance already charged this request; roll back anything this attempt did.
                    status.setRollbackOnly();
                    return QuotaDecision.DUPLICATE;
                }
            }
            if (!increment(userId, feature, QuotaPeriods.Kind.MONTH, monthLimit, now)) {
                status.setRollbackOnly();
                return QuotaDecision.MONTHLY_LIMIT;
            }
            if (!increment(userId, feature, QuotaPeriods.Kind.WEEK, weekLimit, now)) {
                status.setRollbackOnly();
                return QuotaDecision.WEEKLY_LIMIT;
            }
            return QuotaDecision.ALLOWED;
        });
    }

    private boolean increment(UUID userId, UsageFeature feature, QuotaPeriods.Kind kind, int limit, Instant now) {
        String key = QuotaPeriods.key(kind, now);
        return jdbc.update("""
                INSERT INTO usage_quota_counters(user_id, feature, period_kind, period_key, used, updated_at)
                SELECT ?, ?, ?, ?, 1, CURRENT_TIMESTAMP
                WHERE ? > 0
                ON CONFLICT (user_id, feature, period_kind, period_key)
                DO UPDATE SET used = usage_quota_counters.used + 1, updated_at = CURRENT_TIMESTAMP
                WHERE usage_quota_counters.used < ?
                """, userId, feature.name(), kind.name(), key, limit, limit) == 1;
    }

    /** Current-period usage for every feature of one user, read straight from the durable rows. */
    public Map<UsageFeature, PeriodUsage> usage(UUID userId, Instant now) {
        Map<UsageFeature, PeriodUsage> usage = new EnumMap<>(UsageFeature.class);
        jdbc.query("""
                SELECT feature, period_kind, used FROM usage_quota_counters
                WHERE user_id = ? AND period_key IN (?, ?)
                """, row -> {
                    UsageFeature feature = UsageFeature.valueOf(row.getString("feature"));
                    PeriodUsage current = usage.getOrDefault(feature, new PeriodUsage(0, 0));
                    int used = row.getInt("used");
                    usage.put(feature, "MONTH".equals(row.getString("period_kind"))
                            ? new PeriodUsage(used, current.week())
                            : new PeriodUsage(current.month(), used));
                }, userId, QuotaPeriods.key(QuotaPeriods.Kind.MONTH, now),
                QuotaPeriods.key(QuotaPeriods.Kind.WEEK, now));
        return usage;
    }

    public int used(UUID userId, UsageFeature feature, QuotaPeriods.Kind kind, Instant now) {
        Integer used = jdbc.queryForObject("""
                SELECT used FROM usage_quota_counters
                WHERE user_id = ? AND feature = ? AND period_kind = ? AND period_key = ?
                """, Integer.class, userId, feature.name(), kind.name(), QuotaPeriods.key(kind, now));
        return used == null ? 0 : used;
    }

    /** Idempotency marks only need to outlive client retries; the period rows are bounded by design. */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT6H")
    public void purgeStaleMarks() {
        jdbc.update("DELETE FROM usage_quota_requests WHERE created_at < ?",
                Timestamp.from(Instant.now().minus(MARK_RETENTION_DAYS, ChronoUnit.DAYS)));
    }

    public record PeriodUsage(int month, int week) {}
}

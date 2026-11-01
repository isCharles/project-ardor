package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The durable guarantee for business periods: one transaction decides month and week together, so a
 * refusal cannot leave a half-charged state, and the last unit cannot be taken twice by racing
 * instances. Runs against a disposable PostgreSQL schema only.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class QuotaCounterStorePostgresTests {

    @Test
    void aWeeklyCeilingRefusesWithoutLeavingAMonthlyChargeBehind() throws Exception {
        Fixture fixture = fixture();
        UUID user = fixture.user();

        assertThat(fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, fixture.now()))
                .isEqualTo(QuotaDecision.ALLOWED);
        assertThat(fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, fixture.now()))
                .isEqualTo(QuotaDecision.ALLOWED);
        assertThat(fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, fixture.now()))
                .isEqualTo(QuotaDecision.WEEKLY_LIMIT);

        // Both period rows sit at the same value: the monthly increment from the refused call was rolled back.
        assertThat(fixture.jdbc().queryForObject("""
                SELECT used FROM usage_quota_counters WHERE user_id = ? AND period_kind = 'MONTH'
                """, Integer.class, user)).isEqualTo(2);
        assertThat(fixture.counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(2, 2));
    }

    @Test
    void oneRequestIdIsChargedOnceEvenWhenDifferentInstancesRetryIt() throws Exception {
        Fixture fixture = fixture();
        UUID user = fixture.user(), requestId = UUID.randomUUID();
        var otherInstance = new QuotaCounterStore(fixture.jdbc(), fixture.transactions());

        assertThat(fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, fixture.now()))
                .isEqualTo(QuotaDecision.ALLOWED);
        assertThat(otherInstance.consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, fixture.now()))
                .isEqualTo(QuotaDecision.DUPLICATE);

        assertThat(fixture.counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(1, 1));
        assertThat(fixture.store().seen(user, UsageFeature.AGENT_CHAT, requestId)).isTrue();
    }

    @Test
    void twentyFourRacingCallersTakeExactlyFiveUnits() throws Exception {
        Fixture fixture = fixture();
        UUID user = fixture.user();
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24), go = new CountDownLatch(1);
        try {
            List<Future<QuotaDecision>> calls = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                UUID requestId = UUID.randomUUID();
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, fixture.now());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<QuotaDecision> decisions = new ArrayList<>();
            for (var call : calls) decisions.add(call.get(30, TimeUnit.SECONDS));

            assertThat(decisions.stream().filter(QuotaDecision.ALLOWED::equals).count()).isEqualTo(5);
            assertThat(fixture.counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(5, 5));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aZeroCeilingBlocksTheFirstCallAndStoresNothing() throws Exception {
        Fixture fixture = fixture();

        assertThat(fixture.store().consume(fixture.user(), UsageFeature.AGENT_CHAT, 0, 5, null, fixture.now()))
                .isEqualTo(QuotaDecision.MONTHLY_LIMIT);
        assertThat(fixture.counters(UsageFeature.AGENT_CHAT)).isEmpty();
    }

    @Test
    void usageReadsOnlyTheCurrentPeriodRows() throws Exception {
        Fixture fixture = fixture();
        UUID user = fixture.user(), other = UUID.randomUUID();
        fixture.jdbc().update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", other,
                other + "@ardor.invalid", "synthetic-only");
        fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 5, null, fixture.now());
        // A closed period keeps its own row, and another user's spend never shows up here.
        fixture.jdbc().update("""
                INSERT INTO usage_quota_counters(user_id, feature, period_kind, period_key, used)
                VALUES (?, 'AGENT_CHAT', 'MONTH', '2026-10', 99), (?, 'AGENT_CHAT', 'MONTH', '2026-11', 7)
                """, user, other);

        var usage = fixture.store().usage(user, fixture.now());

        assertThat(usage.get(UsageFeature.AGENT_CHAT).month()).isEqualTo(1);
        assertThat(usage.get(UsageFeature.AGENT_CHAT).week()).isEqualTo(1);
        assertThat(fixture.store().used(user, UsageFeature.AGENT_CHAT, QuotaPeriods.Kind.MONTH,
                Instant.parse("2026-10-15T00:00:00Z"))).isEqualTo(99);
    }

    @Test
    void expiredRequestMarkersArePrunedAndFreshOnesSurvive() throws Exception {
        Fixture fixture = fixture();
        UUID user = fixture.user(), stale = UUID.randomUUID(), fresh = UUID.randomUUID();
        fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 5, stale, fixture.now());
        fixture.store().consume(user, UsageFeature.AGENT_CHAT, 5, 5, fresh, fixture.now());
        fixture.jdbc().update("UPDATE usage_quota_requests SET created_at = CURRENT_TIMESTAMP - INTERVAL '45 days' "
                + "WHERE request_id = ?", stale);

        fixture.store().purgeStaleMarks();

        assertThat(fixture.store().seen(user, UsageFeature.AGENT_CHAT, stale)).isFalse();
        assertThat(fixture.store().seen(user, UsageFeature.AGENT_CHAT, fresh)).isTrue();
    }

    private Fixture fixture() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_quota_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user,
                user + "@ardor.invalid", "synthetic-only");
        var transactions = new DataSourceTransactionManager(source);
        return new Fixture(jdbc, transactions, new QuotaCounterStore(jdbc, transactions), user,
                Instant.parse("2026-11-01T10:20:30Z"), schema);
    }

    private record Fixture(JdbcTemplate jdbc, DataSourceTransactionManager transactions, QuotaCounterStore store,
            UUID user, Instant now, String schema) {

        List<Integer> counters(UsageFeature feature) {
            return jdbc.queryForList("""
                    SELECT used FROM usage_quota_counters WHERE user_id = ? AND feature = ?
                    ORDER BY period_kind
                    """, Integer.class, user, feature.name());
        }
    }
}

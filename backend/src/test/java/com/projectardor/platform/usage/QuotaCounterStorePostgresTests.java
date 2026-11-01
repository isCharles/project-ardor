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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The durable guarantee for business periods: one transaction decides month and week together, so a
 * refusal cannot leave a half-charged state, and the last unit cannot be taken twice by racing
 * instances. Only the V96 tables are created here; the full migration chain is covered by the
 * migrate job, and running it per test would hijack the shared `vector` extension.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class QuotaCounterStorePostgresTests {
    private static final Instant NOW = Instant.parse("2026-11-01T10:20:30Z");

    private JdbcTemplate root, jdbc;
    private String schema;
    private QuotaCounterStore store;
    private UUID user;

    @BeforeEach
    void prepare() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_quota_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=" + schema, "ardor", "acceptance-only");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V96__add_durable_quota_counters.sql")).execute(source);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)", user);
        store = new QuotaCounterStore(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach
    void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void aWeeklyCeilingRefusesWithoutLeavingAMonthlyChargeBehind() {
        assertThat(store.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, NOW)).isEqualTo(QuotaDecision.ALLOWED);
        assertThat(store.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, NOW)).isEqualTo(QuotaDecision.ALLOWED);
        assertThat(store.consume(user, UsageFeature.AGENT_CHAT, 5, 2, null, NOW)).isEqualTo(QuotaDecision.WEEKLY_LIMIT);

        // The refused call rolled back its monthly increment too, so both periods sit at two.
        assertThat(counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(2, 2));
    }

    @Test
    void oneRequestIdIsChargedOnceEvenWhenDifferentInstancesRetryIt() {
        UUID requestId = UUID.randomUUID();
        var otherInstance = new QuotaCounterStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThat(store.consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, NOW))
                .isEqualTo(QuotaDecision.ALLOWED);
        assertThat(otherInstance.consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, NOW))
                .isEqualTo(QuotaDecision.DUPLICATE);

        assertThat(counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(1, 1));
        assertThat(store.seen(user, UsageFeature.AGENT_CHAT, requestId)).isTrue();
    }

    @Test
    void twentyFourRacingCallersTakeExactlyFiveUnits() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24), go = new CountDownLatch(1);
        try {
            List<Future<QuotaDecision>> calls = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                UUID requestId = UUID.randomUUID();
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return store.consume(user, UsageFeature.AGENT_CHAT, 5, 5, requestId, NOW);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<QuotaDecision> decisions = new ArrayList<>();
            for (var call : calls) decisions.add(call.get(30, TimeUnit.SECONDS));

            assertThat(decisions.stream().filter(QuotaDecision.ALLOWED::equals).count()).isEqualTo(5);
            assertThat(counters(UsageFeature.AGENT_CHAT)).isEqualTo(List.of(5, 5));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aZeroCeilingBlocksTheFirstCallAndStoresNothing() {
        assertThat(store.consume(user, UsageFeature.AGENT_CHAT, 0, 5, null, NOW))
                .isEqualTo(QuotaDecision.MONTHLY_LIMIT);
        assertThat(counters(UsageFeature.AGENT_CHAT)).isEmpty();
    }

    @Test
    void usageReadsOnlyTheCurrentPeriodRows() {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)", other);
        store.consume(user, UsageFeature.AGENT_CHAT, 5, 5, null, NOW);
        // A closed period keeps its own row, and another user's spend never shows up here.
        jdbc.update("""
                INSERT INTO usage_quota_counters(user_id, feature, period_kind, period_key, used)
                VALUES (?, 'AGENT_CHAT', 'MONTH', '2026-10', 99), (?, 'AGENT_CHAT', 'MONTH', '2026-11', 7)
                """, user, other);

        var usage = store.usage(user, NOW);

        assertThat(usage.get(UsageFeature.AGENT_CHAT).month()).isEqualTo(1);
        assertThat(usage.get(UsageFeature.AGENT_CHAT).week()).isEqualTo(1);
        assertThat(store.used(user, UsageFeature.AGENT_CHAT, QuotaPeriods.Kind.MONTH,
                Instant.parse("2026-10-15T00:00:00Z"))).isEqualTo(99);
    }

    @Test
    void expiredRequestMarkersArePrunedAndFreshOnesSurvive() {
        UUID stale = UUID.randomUUID(), fresh = UUID.randomUUID();
        store.consume(user, UsageFeature.AGENT_CHAT, 5, 5, stale, NOW);
        store.consume(user, UsageFeature.AGENT_CHAT, 5, 5, fresh, NOW);
        jdbc.update("UPDATE usage_quota_requests SET created_at = CURRENT_TIMESTAMP - INTERVAL '45 days' "
                + "WHERE request_id = ?", stale);

        store.purgeStaleMarks();

        assertThat(store.seen(user, UsageFeature.AGENT_CHAT, stale)).isFalse();
        assertThat(store.seen(user, UsageFeature.AGENT_CHAT, fresh)).isTrue();
    }

    private List<Integer> counters(UsageFeature feature) {
        return jdbc.queryForList("""
                SELECT used FROM usage_quota_counters WHERE user_id = ? AND feature = ?
                ORDER BY period_kind
                """, Integer.class, user, feature.name());
    }
}

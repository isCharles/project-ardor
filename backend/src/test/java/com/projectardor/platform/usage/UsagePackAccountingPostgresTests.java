package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Two concurrent calls must not both spend the same pack balance. The first transaction holds the
 * user row while it reads the balance and writes the ledger row; the second can only see what the
 * first committed. This test would record 160 pack tokens against a 100-token pack without that lock.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class UsagePackAccountingPostgresTests {

    @Test
    void concurrentUsageCannotSpendTheSamePackBalanceTwice() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_pack_accounting_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID user = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", user,
                    user + "@ardor.invalid", "synthetic-only");
            // Only 10 plan tokens remain, so each 80-token call needs a pack split.
            jdbc.update("UPDATE membership_plans SET token_monthly_limit = 100 WHERE code = 'FREE'");
            jdbc.update("""
                    INSERT INTO usage_token_ledger(id, user_id, feature, request_id, input_tokens,
                        output_tokens, total_tokens, pack_tokens)
                    VALUES (?, ?, 'AGENT_CHAT', ?, 0, 90, 90, 0)
                    """, UUID.randomUUID(), user, UUID.randomUUID());
            UUID code = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO usage_token_pack_codes(id, code_hash, code_hint, pack_code, max_uses)
                    VALUES (?, ?, ?, 'PACK_1M', 1)
                    """, code, UUID.randomUUID().toString().replace("-", "") + "0000000000000000", "PACK-****");
            jdbc.update("""
                    INSERT INTO usage_token_pack_redemptions(id, code_id, user_id, pack_code, tokens)
                    VALUES (?, ?, ?, 'PACK_1M', 100)
                    """, UUID.randomUUID(), code, user);

            var memberships = new MembershipService(jdbc);
            var plans = new MembershipPlanService(jdbc);
            var packs = new BarrierPackService(jdbc);
            var store = new UsageTokenStore(jdbc, memberships, plans, packs,
                    new DataSourceTransactionManager(source));

            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = executor.submit(() -> recordAfter(start, store, user));
            Future<?> second = executor.submit(() -> recordAfter(start, store, user));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            Long spent = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(pack_tokens), 0) FROM usage_token_ledger WHERE user_id = ?",
                    Long.class, user);
            assertThat(spent).as("two 80-token calls against 100 pack tokens can only spend 100")
                    .isEqualTo(100L);
            assertThat(packs.balance(user)).isZero();
        } finally {
            executor.shutdownNow();
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void recordAfter(CountDownLatch start, UsageTokenStore store, UUID user) {
        try {
            start.await(5, TimeUnit.SECONDS);
            store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 40, 40, "synthetic-model");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Reads before participating in the barrier. Under the fix, the second call cannot reach this
     * method until the first commits; without the fix both callers read 100 and each writes 80.
     */
    static final class BarrierPackService extends UsagePackService {
        private final CountDownLatch observed = new CountDownLatch(2);

        BarrierPackService(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public long balance(UUID userId) {
            long value = super.balance(userId);
            observed.countDown();
            try {
                observed.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return value;
        }
    }
}

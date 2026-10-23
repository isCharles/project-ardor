package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses only a random schema in the explicitly disposable acceptance database. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class RedemptionPostgresTests {
    private JdbcTemplate root, jdbc;
    private String schema;
    private RedemptionService redemptions;
    private TransactionTemplate transaction;
    private final UUID user = UUID.randomUUID();

    @BeforeEach void prepare() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_redeem_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema + "&ApplicationName=" + schema, "ardor", "acceptance-only");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users(id UUID PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE user_memberships (
                    user_id UUID PRIMARY KEY REFERENCES users(id),
                    tier VARCHAR(16) NOT NULL CONSTRAINT user_memberships_tier_check CHECK(tier IN ('FREE','MEMBER')),
                    grant_source VARCHAR(16) NOT NULL CONSTRAINT user_memberships_grant_source_check
                        CHECK(grant_source IN ('GIFT','ADMIN','PAYMENT')),
                    granted_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMPTZ, updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE usage_policies(member_monthly_limit INTEGER NOT NULL)");
        jdbc.update("INSERT INTO users(id) VALUES (?)", user);
        jdbc.update("INSERT INTO user_memberships(user_id,tier,grant_source) VALUES (?,'MEMBER','ADMIN')", user);
        jdbc.update("INSERT INTO usage_policies VALUES (50)");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V61__add_plans_token_usage_and_redemption_codes.sql")).execute(source);
        var memberships = new MembershipService(jdbc);
        redemptions = new RedemptionService(jdbc, memberships, new MembershipPlanService(jdbc));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
    }

    @AfterEach void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test void populatedUpgradePreservesLegacyMembershipAndLimits() {
        assertThat(new MembershipService(jdbc).tier(user)).isEqualTo(MembershipTier.PLUS);
        assertThat(jdbc.queryForObject("SELECT plus_monthly_limit FROM usage_policies", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT pro_monthly_limit FROM usage_policies", Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_plans", Integer.class)).isEqualTo(3);
    }

    @Test void twoDifferentCodesForOneUserExtendInsteadOfOverwritingExpiry() throws Exception {
        jdbc.update("UPDATE user_memberships SET tier='FREE' WHERE user_id=?", user);
        var firstCode = redemptions.generate("PLUS", 30, 1, null, "synthetic");
        var secondCode = redemptions.generate("PLUS", 30, 1, null, "synthetic");
        var firstRedeemed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.execute(status -> {
                var result = redemptions.redeem(user, firstCode.code());
                firstRedeemed.countDown();
                try { if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return result;
            }));
            try {
                assertThat(firstRedeemed.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> transaction.execute(status -> redemptions.redeem(user, secondCode.code())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    if (root.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                            Integer.class, schema) > 0) { blocked = true; break; }
                    Thread.sleep(10);
                }
                assertThat(blocked).as("second redemption must wait for the first transaction").isTrue();
                releaseFirst.countDown();
                var firstResult = first.get(5, TimeUnit.SECONDS);
                var secondResult = second.get(5, TimeUnit.SECONDS);
                assertThat(secondResult.expiresAt()).isCloseTo(firstResult.expiresAt().plusSeconds(30L * 86400),
                        within(1, java.time.temporal.ChronoUnit.MILLIS));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM redemption_redemptions WHERE user_id=?", Integer.class, user)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT SUM(used_count) FROM redemption_codes", Integer.class)).isEqualTo(2);
            } finally { releaseFirst.countDown(); }
        }
    }
}

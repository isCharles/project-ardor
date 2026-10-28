package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentModelSettingsPostgresTests {

    private static final String FINGERPRINT = "c".repeat(64);

    @Test
    void persistsOwnerScopedSettingsWithUpsertDefaultsAndCascade() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_model_settings_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(
                    url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID owner = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            for (UUID user : new UUID[]{owner, other}) {
                jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                        user, user + "@ardor.invalid", "synthetic-only");
            }

            var store = new UserModelSettingsStore(jdbc);
            assertThat(store.findAll(owner)).isEmpty();
            assertThat(store.find(owner, FINGERPRINT)).isNull();
            assertThat(store.findContextWindow(owner, FINGERPRINT)).isNull();

            store.save(owner, FINGERPRINT, "OPENAI_COMPATIBLE", "https://api.deepseek.com",
                    "deepseek-v4-flash", true, "AUTO", null);
            var saved = store.find(owner, FINGERPRINT);
            assertThat(saved).isNotNull();
            assertThat(saved.visible()).isTrue();
            assertThat(saved.thinkingEffort()).isEqualTo("AUTO");
            assertThat(saved.contextWindowTokens()).isNull();
            assertThat(store.findAll(owner)).containsOnlyKeys(FINGERPRINT);
            assertThat(store.findAll(other)).isEmpty();

            store.save(owner, FINGERPRINT, "OPENAI_COMPATIBLE", "https://api.deepseek.com",
                    "deepseek-v4-flash", false, "DEEP", 200_000L);
            var updated = store.find(owner, FINGERPRINT);
            assertThat(updated.visible()).isFalse();
            assertThat(updated.thinkingEffort()).isEqualTo("DEEP");
            assertThat(updated.contextWindowTokens()).isEqualTo(200_000L);
            assertThat(store.findContextWindow(owner, FINGERPRINT)).isEqualTo(200_000L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_model_settings WHERE user_id=?",
                    Integer.class, owner)).isEqualTo(1);

            assertThatThrownBy(() -> store.save(other, "d".repeat(64), "OPENAI_COMPATIBLE",
                    "https://relay.example", "relay-model", true, "EXTREME", null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> store.save(other, "d".repeat(64), "OPENAI_COMPATIBLE",
                    "https://relay.example", "relay-model", true, "AUTO", 1024L))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("DELETE FROM users WHERE id=?", owner);
            assertThat(store.findAll(owner)).isEmpty();
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}

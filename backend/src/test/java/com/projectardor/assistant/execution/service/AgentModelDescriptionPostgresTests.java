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
class AgentModelDescriptionPostgresTests {

    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void persistsOwnerScopedSuccessesFailuresAndConfigurationChanges() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_model_description_" + UUID.randomUUID().toString().replace("-", "");
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

            var store = new AgentModelDescriptionStore(jdbc);
            assertThat(store.find(owner, "example-model", "zh")).isEmpty();

            store.saveSuccess(owner, "example-model", "zh", FINGERPRINT, "高性价比模型");
            var success = store.find(owner, "example-model", "zh").orElseThrow();
            assertThat(success.succeeded()).isTrue();
            assertThat(success.description()).isEqualTo("高性价比模型");
            assertThat(success.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(success.updatedAt()).isNotNull();
            assertThat(store.find(other, "example-model", "zh")).isEmpty();

            store.saveFailure(owner, "example-model", "zh", FINGERPRINT);
            var failure = store.find(owner, "example-model", "zh").orElseThrow();
            assertThat(failure.succeeded()).isFalse();
            assertThat(failure.description()).isNull();
            assertThat(failure.updatedAt()).isAfterOrEqualTo(success.updatedAt());

            String changedFingerprint = "b".repeat(64);
            store.saveSuccess(owner, "example-model", "zh", changedFingerprint, "顶级旗舰模型");
            assertThat(store.find(owner, "example-model", "zh")).get()
                    .extracting(AgentModelDescriptionStore.Entry::fingerprint)
                    .isEqualTo(changedFingerprint);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM model_descriptions WHERE user_id=? AND model_key=? AND locale=?",
                    Integer.class, owner, "example-model", "zh")).isEqualTo(1);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO model_descriptions (user_id, model_key, locale, config_fingerprint, description, failed_at)
                    VALUES (?, ?, ?, ?, NULL, NULL)
                    """, other, "invalid-model", "en", FINGERPRINT))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO model_descriptions (user_id, model_key, locale, config_fingerprint, description, failed_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    """, other, "invalid-model", "fr", FINGERPRINT, "synthetic"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("DELETE FROM users WHERE id=?", owner);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_descriptions WHERE user_id=?",
                    Integer.class, owner)).isZero();
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}

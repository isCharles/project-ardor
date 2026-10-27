package com.projectardor.assistant.execution.service;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentModelDescriptionStore {

    private final JdbcTemplate jdbc;

    public AgentModelDescriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(UUID userId, String modelKey, String locale) {
        return jdbc.query("""
                SELECT config_fingerprint, description, updated_at
                FROM model_descriptions
                WHERE user_id = ? AND model_key = ? AND locale = ?
                """, (rs, row) -> new Entry(
                        rs.getString("config_fingerprint"),
                        rs.getString("description"),
                        rs.getTimestamp("updated_at").toInstant()),
                userId, modelKey, locale).stream().findFirst();
    }

    public void saveSuccess(UUID userId, String modelKey, String locale, String fingerprint, String description) {
        upsert(userId, modelKey, locale, fingerprint, description, null);
    }

    public void saveFailure(UUID userId, String modelKey, String locale, String fingerprint) {
        upsert(userId, modelKey, locale, fingerprint, null, Instant.now());
    }

    private void upsert(UUID userId, String modelKey, String locale, String fingerprint,
            String description, Instant failedAt) {
        jdbc.update("""
                INSERT INTO model_descriptions
                    (user_id, model_key, locale, config_fingerprint, description, failed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, model_key, locale) DO UPDATE SET
                    config_fingerprint = EXCLUDED.config_fingerprint,
                    description = EXCLUDED.description,
                    failed_at = EXCLUDED.failed_at,
                    updated_at = CURRENT_TIMESTAMP
                """, statement -> bind(statement, userId, modelKey, locale, fingerprint, description, failedAt));
    }

    private static void bind(java.sql.PreparedStatement statement, UUID userId, String modelKey, String locale,
            String fingerprint, String description, Instant failedAt) throws SQLException {
        statement.setObject(1, userId, Types.OTHER);
        statement.setString(2, modelKey);
        statement.setString(3, locale);
        statement.setString(4, fingerprint);
        if (description == null) statement.setNull(5, Types.VARCHAR);
        else statement.setString(5, description);
        if (failedAt == null) statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
        else statement.setObject(6, OffsetDateTime.ofInstant(failedAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    public record Entry(String fingerprint, String description, Instant updatedAt) {
        public boolean succeeded() {
            return description != null;
        }
    }
}

package com.projectardor.assistant.execution.service;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserModelSettingsStore {

    private final JdbcTemplate jdbc;

    public UserModelSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Setting> findAll(UUID userId) {
        return jdbc.query("""
                SELECT fingerprint, visible, thinking_effort, context_window_tokens
                FROM user_model_settings
                WHERE user_id = ?
                """, (rs, row) -> new Setting(
                        rs.getString("fingerprint"),
                        rs.getBoolean("visible"),
                        rs.getString("thinking_effort"),
                        rs.getObject("context_window_tokens", Long.class)),
                userId).stream().collect(Collectors.toMap(Setting::fingerprint, Function.identity()));
    }

    public Setting find(UUID userId, String fingerprint) {
        return jdbc.query("""
                SELECT fingerprint, visible, thinking_effort, context_window_tokens
                FROM user_model_settings
                WHERE user_id = ? AND fingerprint = ?
                """, (rs, row) -> new Setting(
                        rs.getString("fingerprint"),
                        rs.getBoolean("visible"),
                        rs.getString("thinking_effort"),
                        rs.getObject("context_window_tokens", Long.class)),
                userId, fingerprint).stream().findFirst().orElse(null);
    }

    public Long findContextWindow(UUID userId, String fingerprint) {
        return jdbc.query("""
                SELECT context_window_tokens
                FROM user_model_settings
                WHERE user_id = ? AND fingerprint = ?
                """, (rs, row) -> rs.getObject("context_window_tokens", Long.class),
                userId, fingerprint).stream().findFirst().orElse(null);
    }

    public void save(UUID userId, String fingerprint, String provider, String baseUrl, String model,
            boolean visible, String thinkingEffort, Long contextWindowTokens) {
        jdbc.update("""
                INSERT INTO user_model_settings
                    (user_id, fingerprint, provider, base_url, model, visible, thinking_effort,
                     context_window_tokens, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, fingerprint) DO UPDATE SET
                    provider = EXCLUDED.provider,
                    base_url = EXCLUDED.base_url,
                    model = EXCLUDED.model,
                    visible = EXCLUDED.visible,
                    thinking_effort = EXCLUDED.thinking_effort,
                    context_window_tokens = EXCLUDED.context_window_tokens,
                    updated_at = CURRENT_TIMESTAMP
                """, statement -> {
            statement.setObject(1, userId, Types.OTHER);
            statement.setString(2, fingerprint);
            statement.setString(3, provider);
            statement.setString(4, baseUrl);
            statement.setString(5, model);
            statement.setBoolean(6, visible);
            statement.setString(7, thinkingEffort);
            if (contextWindowTokens == null) statement.setNull(8, Types.BIGINT);
            else statement.setLong(8, contextWindowTokens);
        });
    }

    public record Setting(String fingerprint, boolean visible, String thinkingEffort, Long contextWindowTokens) {}
}

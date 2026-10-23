package com.projectardor.platform.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable ledger for actual provider token counts. One Agent request is idempotent. */
@Repository
public class UsageTokenStore {

    private static final Logger log = LoggerFactory.getLogger(UsageTokenStore.class);
    private final JdbcTemplate jdbc;

    public UsageTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID userId, UsageFeature feature, UUID requestId, Integer inputTokens,
            Integer outputTokens, String model) {
        long input = Math.max(0, inputTokens == null ? 0 : inputTokens);
        long output = Math.max(0, outputTokens == null ? 0 : outputTokens);
        long total = input + output;
        if (total == 0) return;
        try {
            jdbc.update("""
                    INSERT INTO usage_token_ledger(id, user_id, feature, request_id, input_tokens,
                        output_tokens, total_tokens, model)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), userId, feature.name(), requestId, input, output, total,
                    model == null ? null : model.substring(0, Math.min(160, model.length())));
        } catch (RuntimeException failure) {
            // Token accounting must never turn a successful answer into an error response.
            log.warn("Token usage write dropped: user={}, feature={}, failure={}",
                    userId, feature, failure.getClass().getSimpleName());
        }
    }

    public long usedThisMonth(UUID userId, Instant now) {
        Instant start = LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_tokens), 0) FROM usage_token_ledger
                WHERE user_id = ? AND created_at >= ?
                """, Long.class, userId, java.sql.Timestamp.from(start));
        return total == null ? 0 : total;
    }
}

package com.projectardor.platform.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable ledger for actual provider token counts. One Agent request is idempotent. */
@Repository
public class UsageTokenStore {

    private static final Logger log = LoggerFactory.getLogger(UsageTokenStore.class);
    private final JdbcTemplate jdbc;
    private final MembershipService memberships;
    private final MembershipPlanService plans;
    private final UsagePackService packs;
    private final TransactionTemplate transactions;

    public UsageTokenStore(JdbcTemplate jdbc, MembershipService memberships, MembershipPlanService plans,
            UsagePackService packs, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.memberships = memberships;
        this.plans = plans;
        this.packs = packs;
        this.transactions = new TransactionTemplate(transactionManager);
        // Hold the user lock across the pack split and the ledger insert, and let the answer finish
        // even if accounting is unavailable.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(UUID userId, UsageFeature feature, UUID requestId, Integer inputTokens,
            Integer outputTokens, String model) {
        long input = Math.max(0, inputTokens == null ? 0 : inputTokens);
        long output = Math.max(0, outputTokens == null ? 0 : outputTokens);
        long total = input + output;
        if (total == 0) return;
        if (recordOnce(userId, feature, requestId, input, output, total, model, true)) return;
        // Losing the pack split is better than losing the usage row; plan-only accounting stays correct.
        log.warn("Pack token split unavailable; recording usage without pack attribution: user={}", userId);
        if (!recordOnce(userId, feature, requestId, input, output, total, model, false)) {
            // Token accounting must never turn a successful answer into an error response.
            log.warn("Token usage write dropped: user={}, feature={}", userId, feature);
        }
    }

    private boolean recordOnce(UUID userId, UsageFeature feature, UUID requestId, long input, long output,
            long total, String model, boolean withPack) {
        try {
            transactions.executeWithoutResult(status -> {
                // This is the cross-instance serialization point for pack spending: the balance read
                // and the ledger insert commit together under the same user row lock.
                jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
                long packTokens = withPack ? packShare(userId, total, Instant.now()) : 0;
                jdbc.update("""
                        INSERT INTO usage_token_ledger(id, user_id, feature, request_id, input_tokens,
                            output_tokens, total_tokens, pack_tokens, model)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """, UUID.randomUUID(), userId, feature.name(), requestId, input, output, total, packTokens,
                        model == null ? null : model.substring(0, Math.min(160, model.length())));
            });
            return true;
        } catch (RuntimeException failure) {
            log.warn("Token usage transaction failed: user={}, withPack={}, failure={}",
                    userId, withPack, failure.getClass().getSimpleName());
            return false;
        }
    }

    /** Tokens spent against the monthly plan; pack tokens are excluded so the plan budget stops at its limit. */
    public long usedThisMonth(UUID userId, Instant now) {
        Instant start = LocalDate.of(now.atZone(ZoneOffset.UTC).getYear(),
                now.atZone(ZoneOffset.UTC).getMonthValue(), 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_tokens - pack_tokens), 0) FROM usage_token_ledger
                WHERE user_id = ? AND created_at >= ?
                """, Long.class, userId, java.sql.Timestamp.from(start));
        return total == null ? 0 : total;
    }

    private long packShare(UUID userId, long total, Instant now) {
        long limit = plans.tokenLimit(memberships.tier(userId));
        if (limit <= 0) return 0;
        long planRemaining = Math.max(0, limit - usedThisMonth(userId, now));
        long overflow = Math.max(0, total - planRemaining);
        if (overflow == 0) return 0;
        return Math.min(overflow, packs.balance(userId));
    }
}

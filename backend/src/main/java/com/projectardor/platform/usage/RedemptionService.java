package com.projectardor.platform.usage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;

/** Administrator-issued upgrade codes; the only upgrade path until a payment provider exists. */
@Service
public class RedemptionService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final MembershipService memberships;
    private final MembershipPlanService plans;

    public RedemptionService(JdbcTemplate jdbc, MembershipService memberships, MembershipPlanService plans) {
        this.jdbc = jdbc;
        this.memberships = memberships;
        this.plans = plans;
    }

    @Transactional
    public GeneratedCode generate(String planTier, int durationDays, int maxUses, Instant expiresAt, String note) {
        MembershipTier tier = paidTier(planTier);
        if (durationDays <= 0 || durationDays > 3650) throw new IllegalArgumentException("有效期需在 1 到 3650 天之间");
        if (maxUses <= 0 || maxUses > 10000) throw new IllegalArgumentException("可使用次数需在 1 到 10000 之间");
        String code = newCode();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO redemption_codes(id, code_hash, code_hint, plan_tier, duration_days, max_uses,
                    expires_at, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, hash(code), hint(code), tier.name(), durationDays, maxUses,
                expiresAt == null ? null : Timestamp.from(expiresAt), trim(note));
        return new GeneratedCode(id, code, hint(code), tier.name(), durationDays, maxUses, 0,
                expiresAt, Instant.now());
    }

    public List<CodeView> list() {
        return jdbc.query("""
                SELECT id, code_hint, plan_tier, duration_days, max_uses, used_count, expires_at, note, created_at
                FROM redemption_codes ORDER BY created_at DESC
                """, (row, index) -> new CodeView(row.getObject("id", UUID.class), row.getString("code_hint"),
                row.getString("plan_tier"), row.getInt("duration_days"), row.getInt("max_uses"),
                row.getInt("used_count"), instant(row.getTimestamp("expires_at")),
                row.getString("note"), row.getTimestamp("created_at").toInstant()));
    }

    @Transactional
    public Redeemed redeem(UUID userId, String rawCode) {
        String code = normalize(rawCode);
        if (code.length() < 8) throw new IllegalArgumentException("兑换码格式不正确");
        // Different codes must not concurrently overwrite the same user's paid expiry.
        // Lock the stable user row first: a new account may have no membership row yet.
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId).isEmpty()) {
            throw new ResourceNotFoundException("用户不存在");
        }
        List<CodeRow> rows = jdbc.query("""
                SELECT * FROM redemption_codes WHERE code_hash = ? FOR UPDATE
                """, (row, index) -> new CodeRow(row.getObject("id", UUID.class), row.getString("plan_tier"),
                row.getInt("duration_days"), row.getInt("max_uses"), row.getInt("used_count"),
                instant(row.getTimestamp("expires_at"))), hash(code));
        if (rows.isEmpty()) throw new ResourceNotFoundException("兑换码不存在或已失效");
        CodeRow row = rows.getFirst();
        Instant now = Instant.now();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("兑换码已过期");
        }
        if (row.usedCount() >= row.maxUses()) throw new IllegalArgumentException("兑换码已被用完");
        MembershipTier tier = MembershipTier.valueOf(row.planTier());
        MembershipTier current = memberships.tier(userId);
        if (current != MembershipTier.FREE && !tier.atLeast(current)) {
            throw new IllegalArgumentException("当前套餐不低于该兑换码");
        }
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM redemption_redemptions WHERE code_id = ? AND user_id = ?
                """, Integer.class, row.id(), userId);
        if (already != null && already > 0) throw new IllegalArgumentException("你已经兑换过这个兑换码");
        Instant base = current == tier ? memberships.expiry(userId) : null;
        Instant startsAt = base != null && base.isAfter(now) ? base : now;
        Instant expiresAt = startsAt.plus(row.durationDays(), ChronoUnit.DAYS);
        UUID redemptionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO redemption_redemptions(id, code_id, user_id, expires_at) VALUES (?, ?, ?, ?)
                """, redemptionId, row.id(), userId, Timestamp.from(expiresAt));
        jdbc.update("""
                UPDATE redemption_codes SET used_count = used_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND used_count < max_uses
                """, row.id());
        memberships.grant(userId, tier, "REDEEM", expiresAt);
        return new Redeemed(tier.name(), expiresAt, plans.get(tier).nameZh(), plans.get(tier).nameEn());
    }

    private static MembershipTier paidTier(String raw) {
        try {
            MembershipTier tier = MembershipTier.valueOf(raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT));
            if (!tier.paid()) throw new IllegalArgumentException("兑换码只能配置 Plus 或 Pro 套餐");
            return tier;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("兑换码只能配置 Plus 或 Pro 套餐");
        }
    }

    private static String newCode() {
        StringBuilder value = new StringBuilder("ARDOR-");
        for (int group = 0; group < 2; group++) {
            if (group > 0) value.append('-');
            for (int index = 0; index < 4; index++) value.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalize(code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成兑换码摘要", exception);
        }
    }

    private static String hint(String code) {
        return "••••" + code.substring(code.length() - 4);
    }

    private static String trim(String value) {
        if (value == null) return null;
        return value.length() <= 200 ? value.strip() : value.strip().substring(0, 200);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record CodeRow(UUID id, String planTier, int durationDays, int maxUses, int usedCount,
            Instant expiresAt) {
    }

    public record GeneratedCode(UUID id, String code, String codeHint, String planTier, int durationDays,
            int maxUses, int usedCount, Instant expiresAt, Instant createdAt) {
    }

    public record CodeView(UUID id, String codeHint, String planTier, int durationDays, int maxUses,
            int usedCount, Instant expiresAt, String note, Instant createdAt) {
    }

    public record Redeemed(String tier, Instant expiresAt, String nameZh, String nameEn) {
    }
}

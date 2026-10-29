package com.projectardor.platform.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;

/** Token resource packs: monthly plan budget is spent first, then redeemed pack tokens. */
@Service
public class UsagePackService {

    private final JdbcTemplate jdbc;

    public UsagePackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Pack> list() {
        return jdbc.query("SELECT * FROM usage_token_packs WHERE active ORDER BY sort_order, code", this::map);
    }

    public Pack get(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return jdbc.query("SELECT * FROM usage_token_packs WHERE code = ?", this::map, normalized)
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("资源包不存在"));
    }

    /** Redeemed tokens minus what was already spent from packs; packs do not expire across months. */
    public long balance(UUID userId) {
        Long redeemed = jdbc.queryForObject("""
                SELECT COALESCE(SUM(tokens), 0) FROM usage_token_pack_redemptions WHERE user_id = ?
                """, Long.class, userId);
        Long spent = jdbc.queryForObject("""
                SELECT COALESCE(SUM(pack_tokens), 0) FROM usage_token_ledger WHERE user_id = ?
                """, Long.class, userId);
        return Math.max(0, (redeemed == null ? 0 : redeemed) - (spent == null ? 0 : spent));
    }

    @Transactional
    public GeneratedCode generate(String packCode, int maxUses, Instant expiresAt, String note) {
        if (maxUses <= 0 || maxUses > 10000) throw new IllegalArgumentException("可使用次数需在 1 到 10000 之间");
        Pack pack = get(packCode);
        String code = RedemptionCodeFormat.newCode("PACK");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usage_token_pack_codes(id, code_hash, code_hint, pack_code, max_uses, expires_at, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, RedemptionCodeFormat.hash(code), RedemptionCodeFormat.hint(code), pack.code(), maxUses,
                expiresAt == null ? null : Timestamp.from(expiresAt), RedemptionCodeFormat.trimNote(note));
        return new GeneratedCode(id, code, RedemptionCodeFormat.hint(code), pack.code(), maxUses, 0,
                expiresAt, Instant.now());
    }

    public List<CodeView> listCodes() {
        return jdbc.query("""
                SELECT id, code_hint, pack_code, max_uses, used_count, expires_at, note, created_at
                FROM usage_token_pack_codes ORDER BY created_at DESC
                """, (row, index) -> new CodeView(row.getObject("id", UUID.class), row.getString("code_hint"),
                row.getString("pack_code"), row.getInt("max_uses"), row.getInt("used_count"),
                RedemptionCodeFormat.instant(row.getTimestamp("expires_at")), row.getString("note"),
                row.getTimestamp("created_at").toInstant()));
    }

    @Transactional
    public Redeemed redeem(UUID userId, String rawCode) {
        String code = RedemptionCodeFormat.normalize(rawCode);
        if (code.length() < 8) throw new IllegalArgumentException("兑换码格式不正确");
        // Lock the user row first so two different pack codes cannot race the same balance read.
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId).isEmpty()) {
            throw new ResourceNotFoundException("用户不存在");
        }
        List<CodeRow> rows = jdbc.query("""
                SELECT id, pack_code, max_uses, used_count, expires_at FROM usage_token_pack_codes
                WHERE code_hash = ? FOR UPDATE
                """, (row, index) -> new CodeRow(row.getObject("id", UUID.class), row.getString("pack_code"),
                row.getInt("max_uses"), row.getInt("used_count"),
                RedemptionCodeFormat.instant(row.getTimestamp("expires_at"))),
                RedemptionCodeFormat.hash(code));
        if (rows.isEmpty()) throw new ResourceNotFoundException("兑换码不存在或已失效");
        CodeRow row = rows.getFirst();
        Instant now = Instant.now();
        if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) throw new IllegalArgumentException("兑换码已过期");
        if (row.usedCount() >= row.maxUses()) throw new IllegalArgumentException("兑换码已被用完");
        Integer already = jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_token_pack_redemptions WHERE code_id = ? AND user_id = ?
                """, Integer.class, row.id(), userId);
        if (already != null && already > 0) throw new IllegalArgumentException("你已经兑换过这个兑换码");
        Pack pack = get(row.packCode());
        jdbc.update("""
                INSERT INTO usage_token_pack_redemptions(id, code_id, user_id, pack_code, tokens)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), row.id(), userId, pack.code(), pack.tokenAmount());
        jdbc.update("""
                UPDATE usage_token_pack_codes SET used_count = used_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND used_count < max_uses
                """, row.id());
        return new Redeemed(pack.code(), pack.nameZh(), pack.nameEn(), pack.tokenAmount(), balance(userId));
    }

    private Pack map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new Pack(row.getString("code"), row.getString("name_zh"), row.getString("name_en"),
                row.getLong("token_amount"), row.getInt("price_cents"), row.getString("currency"));
    }

    private record CodeRow(UUID id, String packCode, int maxUses, int usedCount, Instant expiresAt) {
    }

    public record Pack(String code, String nameZh, String nameEn, long tokenAmount, int priceCents,
            String currency) {}

    public record GeneratedCode(UUID id, String code, String codeHint, String packCode, int maxUses,
            int usedCount, Instant expiresAt, Instant createdAt) {}

    public record CodeView(UUID id, String codeHint, String packCode, int maxUses, int usedCount,
            Instant expiresAt, String note, Instant createdAt) {}

    public record Redeemed(String packCode, String nameZh, String nameEn, long tokens, long balance) {}
}

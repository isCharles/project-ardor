package com.projectardor.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class MembershipService {
    private final JdbcTemplate jdbc;

    public MembershipService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void grantWelcomeMembership(UUID userId) {
        jdbc.update("""
                INSERT INTO user_memberships (user_id, tier, grant_source) VALUES (?, 'MEMBER', 'GIFT')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
    }

    public MembershipTier tier(UUID userId) {
        return jdbc.query("SELECT tier, expires_at FROM user_memberships WHERE user_id = ?", row -> {
            if (!row.next()) return MembershipTier.FREE;
            return effectiveTier(row.getString("tier"), row.getTimestamp("expires_at"));
        }, userId);
    }

    public Map<UUID, MembershipTier> tiers(List<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        Map<UUID, MembershipTier> result = new HashMap<>();
        jdbc.query("SELECT user_id, tier, expires_at FROM user_memberships WHERE user_id IN ("
                + placeholders + ")", row -> {
            result.put(row.getObject("user_id", UUID.class),
                    effectiveTier(row.getString("tier"), row.getTimestamp("expires_at")));
        }, userIds.toArray());
        return result;
    }

    private MembershipTier effectiveTier(String tier, Timestamp expiry) {
        return expiry != null && !expiry.toInstant().isAfter(Instant.now())
                ? MembershipTier.FREE : MembershipTier.valueOf(tier);
    }

    @Transactional
    public MembershipTier setTier(UUID userId, MembershipTier tier) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new ResourceNotFoundException("用户不存在");
        jdbc.update("""
                INSERT INTO user_memberships (user_id, tier, grant_source, granted_at, expires_at)
                VALUES (?, ?, 'ADMIN', CURRENT_TIMESTAMP, NULL)
                ON CONFLICT (user_id) DO UPDATE SET tier = EXCLUDED.tier,
                    grant_source = EXCLUDED.grant_source, granted_at = EXCLUDED.granted_at,
                    expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                """, userId, tier.name());
        return tier;
    }
}

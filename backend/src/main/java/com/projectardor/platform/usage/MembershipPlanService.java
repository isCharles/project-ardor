package com.projectardor.platform.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;

/** Plan catalog and per-plan token budgets. Prices are informational until payment is enabled. */
@Service
public class MembershipPlanService {

    private final JdbcTemplate jdbc;

    public MembershipPlanService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> list() {
        return jdbc.query("SELECT * FROM membership_plans WHERE active ORDER BY sort_order, code", this::map);
    }

    public Plan get(MembershipTier tier) {
        return jdbc.query("SELECT * FROM membership_plans WHERE code = ?", this::map, tier.name())
                .stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("套餐不存在"));
    }

    public long tokenLimit(MembershipTier tier) {
        return get(tier).tokenMonthlyLimit();
    }

    @Transactional
    public Plan update(MembershipTier tier, PlanUpdate request) {
        int changed = jdbc.update("""
                UPDATE membership_plans SET price_cents = ?, token_monthly_limit = ?,
                    payment_enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE code = ?
                """, request.priceCents(), request.tokenMonthlyLimit(), request.paymentEnabled(), tier.name());
        if (changed != 1) throw new ResourceNotFoundException("套餐不存在");
        return get(tier);
    }

    private Plan map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new Plan(MembershipTier.valueOf(row.getString("code")), row.getString("name_zh"),
                row.getString("name_en"), row.getInt("price_cents"), row.getString("currency"),
                row.getString("billing_interval"), row.getLong("token_monthly_limit"),
                row.getBoolean("payment_enabled"), row.getInt("sort_order"),
                row.getTimestamp("updated_at").toInstant());
    }

    public record Plan(MembershipTier code, String nameZh, String nameEn, int priceCents, String currency,
            String billingInterval, long tokenMonthlyLimit, boolean paymentEnabled, int sortOrder,
            Instant updatedAt) {
    }

    public record PlanUpdate(int priceCents, long tokenMonthlyLimit, boolean paymentEnabled) {
        public PlanUpdate {
            if (priceCents < 0 || tokenMonthlyLimit < 0) throw new IllegalArgumentException("套餐价格和 Token 额度不能为负");
        }
    }
}

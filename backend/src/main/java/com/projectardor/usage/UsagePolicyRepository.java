package com.projectardor.usage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UsagePolicyRepository {
    private final JdbcTemplate jdbc;

    public UsagePolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UsagePolicy get(UsageFeature feature) {
        return jdbc.query("SELECT * FROM usage_policies WHERE feature = ?", this::map, feature.name())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少功能额度配置：" + feature));
    }

    public List<UsagePolicy> list() {
        return jdbc.query("SELECT * FROM usage_policies ORDER BY feature", this::map);
    }

    public UsagePolicy update(UsageFeature feature, int freeMonthly, int memberMonthly,
            int userMinute, int globalMinute) {
        new UsagePolicy(feature, freeMonthly, memberMonthly, userMinute, globalMinute, null);
        int changed = jdbc.update("""
                UPDATE usage_policies SET free_monthly_limit = ?, member_monthly_limit = ?,
                    user_minute_limit = ?, global_minute_limit = ?, updated_at = CURRENT_TIMESTAMP
                WHERE feature = ?
                """, freeMonthly, memberMonthly, userMinute, globalMinute, feature.name());
        if (changed != 1) throw new IllegalStateException("缺少功能额度配置：" + feature);
        return get(feature);
    }

    private UsagePolicy map(ResultSet row, int ignored) throws SQLException {
        return new UsagePolicy(UsageFeature.valueOf(row.getString("feature")),
                row.getInt("free_monthly_limit"), row.getInt("member_monthly_limit"),
                row.getInt("user_minute_limit"), row.getInt("global_minute_limit"),
                row.getTimestamp("updated_at").toInstant());
    }
}

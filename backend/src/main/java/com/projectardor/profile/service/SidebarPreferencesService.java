package com.projectardor.profile.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

/** Account-owned UI preferences; these never grant business capabilities. */
@Service
public class SidebarPreferencesService {
    private static final List<String> DEFAULTS = List.of("/app", "/app/resumes", "/app/interviews", "/app/opportunities", "/app/cards", "/app/calendar", "/app/applications");
    private static final Set<String> ALLOWED = Set.of("/app", "/app/resumes", "/app/interviews", "/app/opportunities", "/app/recaps", "/app/cards",
            "/app/knowledge", "/app/learning", "/app/calendar", "/app/applications", "/app/evidence", "/app/tasks");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SidebarPreferencesService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public View get(UUID owner) {
        var rows = jdbc.queryForList("SELECT COALESCE(preferences->'sidebar', 'null'::jsonb)::text FROM user_profiles WHERE user_id=?", String.class, owner);
        if (rows.isEmpty()) throw new ResourceNotFoundException("用户资料不存在");
        if ("null".equals(rows.getFirst())) return new View(owner, DEFAULTS, 0, false);
        var saved = mapper.readValue(rows.getFirst(), Saved.class);
        return new View(owner, normalize(saved.hrefs()), saved.revision(), true);
    }

    @Transactional
    public View save(UUID owner, List<String> hrefs, long expectedRevision) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) throw new IllegalArgumentException("侧栏配置版本无效");
        var normalized = normalize(hrefs);
        String json = mapper.writeValueAsString(Map.of("hrefs", normalized, "revision", expectedRevision + 1));
        int changed = jdbc.update("""
                UPDATE user_profiles SET preferences=jsonb_set(preferences, '{sidebar}', CAST(? AS jsonb)),updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND COALESCE((preferences->'sidebar'->>'revision')::bigint,0)=?
                """, json, owner, expectedRevision);
        if (changed != 1) {
            get(owner); // Distinguish an absent owned profile from an optimistic concurrency conflict.
            throw new IllegalStateException("其他设备已更新侧栏，请重新读取后再保存；未覆盖账户配置");
        }
        return new View(owner, normalized, expectedRevision + 1, true);
    }

    private List<String> normalize(List<String> hrefs) {
        if (hrefs == null || hrefs.size() > ALLOWED.size() || hrefs.stream().anyMatch(href -> href == null || !ALLOWED.contains(href))) {
            throw new IllegalArgumentException("侧栏模块无效");
        }
        var unique = new LinkedHashSet<String>();
        unique.add("/app"); unique.addAll(hrefs);
        return List.copyOf(unique);
    }
    private record Saved(List<String> hrefs, long revision) {}
    public record View(UUID userId, List<String> hrefs, long revision, boolean configured) {}
}

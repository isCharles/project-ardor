package com.projectardor.platform.identity.profile.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

/** Account-owned UI preferences; these never grant business capabilities. */
@Service
public class SidebarPreferencesService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NavigationCatalog catalog;
    public SidebarPreferencesService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper; this.catalog = new NavigationCatalog(mapper);
    }

    public View get(UUID owner) {
        var rows = jdbc.queryForList("SELECT preferences::text FROM user_profiles WHERE user_id=?", String.class, owner);
        if (rows.isEmpty()) throw new ResourceNotFoundException("用户资料不存在");
        var root = mapper.readTree(rows.getFirst());
        var sidebar = root.path("sidebar");
        if (sidebar.isMissingNode() || sidebar.isNull()) return view(owner, catalog.defaults(), 0, false);
        long revision = sidebar.path("revision").asLong();
        var navigation = root.path("navigation");
        var ids = navigation.isObject() && navigation.path("revision").asLong(-1) == revision
                ? catalog.normalizeIds(NavigationCatalog.strings(navigation.path("destinationIds")))
                : catalog.fromLegacy(NavigationCatalog.strings(sidebar.path("hrefs")), List.of());
        return view(owner, ids, revision, true);
    }

    @Transactional
    public View save(UUID owner, List<String> hrefs, long expectedRevision) {
        catalog.fromLegacy(hrefs, List.of()); // Validate before reading or writing anything.
        return saveDestinations(owner, catalog.fromLegacy(hrefs, get(owner).destinationIds()), expectedRevision);
    }

    @Transactional
    public View saveDestinations(UUID owner, List<String> destinationIds, long expectedRevision) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) throw new IllegalArgumentException("侧栏配置版本无效");
        var ids = catalog.normalizeIds(destinationIds);
        String sidebar = mapper.writeValueAsString(Map.of("hrefs", catalog.legacyHrefs(ids), "revision", expectedRevision + 1));
        String navigation = mapper.writeValueAsString(Map.of("destinationIds", ids, "revision", expectedRevision + 1));
        int changed = jdbc.update("""
                UPDATE user_profiles SET preferences=jsonb_set(jsonb_set(preferences, '{sidebar}', CAST(? AS jsonb)), '{navigation}', CAST(? AS jsonb)),updated_at=CURRENT_TIMESTAMP
                WHERE user_id=? AND COALESCE((preferences->'sidebar'->>'revision')::bigint,0)=?
                """, sidebar, navigation, owner, expectedRevision);
        if (changed != 1) {
            get(owner); // Distinguish an absent owned profile from an optimistic concurrency conflict.
            throw new IllegalStateException("其他设备已更新侧栏，请重新读取后再保存；未覆盖账户配置");
        }
        return view(owner, ids, expectedRevision + 1, true);
    }

    private View view(UUID owner, List<String> ids, long revision, boolean configured) {
        return new View(owner, catalog.legacyHrefs(ids), revision, configured, ids);
    }
    public record View(UUID userId, List<String> hrefs, long revision, boolean configured, List<String> destinationIds) {}
}

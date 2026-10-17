package com.projectardor.agent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.projectardor.agent.capability.Capability;
import com.projectardor.agent.capability.ToolCatalog;
import com.projectardor.agent.capability.ToolModule;

/**
 * Agent grants live in one row per module plus one global row ({@code *}).
 * A module row overrides the global default; without one the module follows it.
 * Level 0 preserves the original behaviour: every write asks for confirmation.
 * Deleting is never auto-approved regardless of level.
 */
@Service
public class AgentPermissionGrants {

    public static final int MAX_LEVEL = 3;
    static final String GLOBAL_MODULE = "*";
    private final JdbcTemplate jdbc;

    public AgentPermissionGrants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Permissions(int globalLevel, Map<ToolModule, Integer> modules) {}

    /** Effective levels plus each module's override state for the settings page. */
    public Permissions permissions(UUID userId) {
        Map<String, Integer> stored = storedLevels(userId);
        int global = stored.getOrDefault(GLOBAL_MODULE, 0);
        Map<ToolModule, Integer> modules = new EnumMap<>(ToolModule.class);
        for (ToolModule module : ToolModule.values()) {
            modules.put(module, stored.getOrDefault(module.key(), global));
        }
        return new Permissions(global, modules);
    }

    private Map<String, Integer> storedLevels(UUID userId) {
        Map<String, Integer> levels = new java.util.HashMap<>();
        jdbc.query("SELECT module, level FROM agent_permission_grants WHERE user_id = ?",
                resultSet -> {
                    levels.put(resultSet.getString("module"), resultSet.getInt("level"));
                }, userId);
        return levels;
    }

    public int globalLevel(UUID userId) {
        Integer level = jdbc.query("SELECT level FROM agent_permission_grants WHERE user_id = ? AND module = ?",
                resultSet -> resultSet.next() ? resultSet.getInt(1) : null, userId, GLOBAL_MODULE);
        return level == null ? 0 : level;
    }

    public OptionalInt moduleOverride(UUID userId, ToolModule module) {
        Integer level = jdbc.query("SELECT level FROM agent_permission_grants WHERE user_id = ? AND module = ?",
                resultSet -> resultSet.next() ? resultSet.getInt(1) : null, userId, module.key());
        return level == null ? OptionalInt.empty() : OptionalInt.of(level);
    }

    /** Effective level: a module override wins, otherwise the global default applies. */
    public int level(UUID userId, ToolModule module) {
        return moduleOverride(userId, module).orElseGet(() -> globalLevel(userId));
    }

    public void setGlobalLevel(UUID userId, int level) {
        write(userId, GLOBAL_MODULE, level);
    }

    public void setModuleLevel(UUID userId, ToolModule module, int level) {
        write(userId, module.key(), level);
    }

    /** Removes the module override so the module follows the global level again. */
    public void clearModuleLevel(UUID userId, ToolModule module) {
        jdbc.update("DELETE FROM agent_permission_grants WHERE user_id = ? AND module = ?", userId, module.key());
    }

    private void write(UUID userId, String module, int level) {
        if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("权限等级须在 0 到 3 之间");
        jdbc.update("""
                INSERT INTO agent_permission_grants(user_id, module, level)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, module) DO UPDATE SET level = EXCLUDED.level, updated_at = CURRENT_TIMESTAMP
                """, userId, module, level);
    }

    /** True only for a registered, non-destructive tool whose module level covers its capability. */
    public boolean autoApproves(UUID userId, String tool) {
        ToolCatalog.Risk risk = ToolCatalog.describe(tool);
        if (risk == null || risk.capability().requiresProposal()) return false;
        return level(userId, risk.module()) >= risk.capability().minimumLevel();
    }

    public void recordAutoApproval(UUID userId, UUID runId, String tool, String arguments) {
        ToolCatalog.Risk risk = ToolCatalog.describe(tool);
        if (risk == null) return;
        jdbc.update("""
                INSERT INTO agent_tool_audit(id, user_id, run_id, tool, module, capability, arguments_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, runId, tool, risk.module().key(),
                risk.capability().name(), digest(arguments));
    }

    static String digest(String arguments) {
        String value = arguments == null ? "" : arguments;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** Level a user needs to stop confirming this capability; used by the approval dialog. */
    public static int suggestedLevel(Capability capability) {
        return capability.minimumLevel();
    }
}

package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.projectardor.assistant.tools.capability.ToolModule;

/** A populated V64 -> V65 upgrade, only in a random disposable schema. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentApprovalModePostgresTests {
    @Test void preservesLegacyGrantsAndModeSelectionIsOwnedAndReversible() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_approval_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("64").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            for (UUID id : new UUID[]{owner, other}) jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                    id, id + "@ardor.invalid", "synthetic-only");
            jdbc.update("INSERT INTO agent_permission_grants(user_id,module,level) VALUES(?,'*',3),(?,'calendar',0),(?,'*',1)", owner, owner, other);
            var before = jdbc.queryForList("SELECT user_id,module,level FROM agent_permission_grants ORDER BY user_id,module");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("65").load().migrate();
            assertThat(jdbc.queryForList("SELECT user_id,module,level FROM agent_permission_grants ORDER BY user_id,module")).isEqualTo(before);
            var grants = new AgentPermissionGrants(jdbc);
            assertThat(grants.mode(owner)).isEqualTo(AgentApprovalMode.CUSTOM);
            assertThat(grants.autoApproves(owner, "update_calendar_task")).isFalse();
            assertThat(grants.autoApproves(owner, "create_memory_card")).isTrue();
            grants.setMode(owner, AgentApprovalMode.FULL);
            assertThat(grants.autoApproves(owner, "update_calendar_task")).isTrue();
            assertThat(grants.autoApproves(owner, "delete_calendar_task")).isFalse();
            assertThat(grants.mode(other)).isEqualTo(AgentApprovalMode.CUSTOM);
            assertThat(grants.autoApproves(other, "update_calendar_task")).isFalse();
            grants.setMode(owner, AgentApprovalMode.ASK);
            assertThat(grants.autoApproves(owner, "create_memory_card")).isFalse();
            grants.setMode(owner, AgentApprovalMode.AUTO);
            assertThat(grants.autoApproves(owner, "update_calendar_task")).isTrue();
            assertThat(grants.autoApproves(owner, "update_recurring_task")).isFalse();
            assertThat(grants.requiresConfirmationForBatch(owner, 3)).isFalse();
            assertThat(grants.requiresConfirmationForBatch(owner, 4)).isTrue();
            assertThat(jdbc.queryForList("SELECT user_id,module,level FROM agent_permission_grants ORDER BY user_id,module")).isEqualTo(before);
            grants.setMode(owner, AgentApprovalMode.CUSTOM);
            assertThat(grants.autoApproves(owner, "update_calendar_task")).isFalse();
            grants.setMode(owner, AgentApprovalMode.FULL);
            grants.setModuleLevel(owner, ToolModule.CALENDAR, 2);
            assertThat(grants.mode(owner)).isEqualTo(AgentApprovalMode.CUSTOM);
            assertThat(grants.autoApproves(owner, "update_calendar_task")).isTrue();
            grants.setMode(owner, AgentApprovalMode.FULL);
            grants.clearModuleLevel(owner, ToolModule.CALENDAR);
            assertThat(grants.mode(owner)).isEqualTo(AgentApprovalMode.CUSTOM);
            grants.setMode(owner, AgentApprovalMode.FULL);
            grants.setGlobalLevel(owner, 0);
            assertThat(grants.mode(owner)).isEqualTo(AgentApprovalMode.CUSTOM);
            assertThat(grants.autoApproves(owner, "create_memory_card")).isFalse();
            assertThat(grants.autoApproves(other, "create_memory_card")).isTrue();
        } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
}

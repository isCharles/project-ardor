package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Connection-local table in the explicitly disposable database. No shared rows are read. */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentContextProgressPostgresTests {
    @Test void durableContextIsOwnedAndOlderCheckpointsCannotReplaceIt() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        try (var connection = DriverManager.getConnection(url, "ardor", "acceptance-only")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TEMP TABLE agent_runs(id uuid PRIMARY KEY,user_id uuid,status text,created_at timestamptz,finished_at timestamptz,progress_snapshot jsonb)");
            UUID owner = UUID.randomUUID(), run = UUID.randomUUID(), other = UUID.randomUUID();
            jdbc.update("INSERT INTO agent_runs(id,user_id,status,created_at,finished_at) VALUES(?,?,'COMPLETED',now()-interval '5 seconds',now())", run, owner);
            var store = new AgentProgressStore(jdbc, JsonMapper.builder().build());
            var live = new AgentStreamProgress(store);
            live.begin(owner, run);
            var usage = new AgentContextUsage("READY", "synthetic-model", 1800L, 12000, null, 2, 8);
            live.context(run, usage); live.finish(run, false);
            var snapshot = store.find(owner, run).orElseThrow().snapshot();
            var stale = new AgentStreamProgress.Snapshot(0, Instant.now(), Instant.now(),
                    new AgentStreamProgress.View("WAITING", "old", "", "", null, null, 0, false, java.util.List.of(), 0));
            store.save(owner, run, stale); store.save(other, run, new AgentStreamProgress.Snapshot(snapshot.revision()+1, stale.startedAt(), stale.lastData(), stale.view()));
            assertThat(store.find(other, run)).isEmpty();
            assertThat(store.find(owner, run).orElseThrow().snapshot()).isEqualTo(snapshot);
            var restarted = new AgentStreamProgress(store);
            assertThat(restarted.get(owner, run).contextUsage()).isEqualTo(usage);
            assertThat(restarted.get(owner, run).elapsedMs()).isBetween(4900L, 5500L);
            assertThatThrownBy(() -> restarted.get(other, run)).isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
        }
    }
}

package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * A compaction span must survive the real database constraint, because the trace writer fails soft:
 * a rejected kind is only a warning log and the span silently disappears from the graph.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentCompactionTraceKindPostgresTests {

    @Test void compactionSpanPersistsAndMergesTheOutcomeLearnedAfterItFinished() {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        String schema = "ardor_compaction_kind_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        try {
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                    "ardor", "acceptance-only");
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID(), run = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", owner, owner + "@ardor.invalid", "synthetic-only");
            jdbc.update("INSERT INTO conversations(id,user_id) VALUES(?,?)", conversation, owner);
            jdbc.update("INSERT INTO agent_runs(id,user_id,conversation_id,request_hash,message,status) VALUES(?,?,?,?,'Synthetic turn','COMPLETED')",
                    run, owner, conversation, "x".repeat(64));
            var store = new AgentTraceStore(jdbc, JsonMapper.builder().build(), null, true, 30);
            UUID rootSpan = store.startSpan(owner, run, null, "RUN", "agent.run", Map.of());
            assertThat(rootSpan).isNotNull();

            UUID span = store.startSpan(owner, run, rootSpan, "COMPACTION", "context.compact.1", Map.of(
                    "windowTokens", 65536, "softThresholdTokens", 43622, "projectedInputTokens", 57432,
                    "messagesToSummarize", 3, "firstMessageOrdinal", 1, "summaryVersionBefore", 0));
            assertThat(span).as("the kind CHECK constraint must accept COMPACTION").isNotNull();
            store.finishSpan(owner, span, "COMPLETED", null, "synthetic-summarizer", null, 57432, 1200, null, null,
                    Map.of("outcome", "COMPACTED"));
            store.annotateSpan(owner, span, Map.of("estimatedInputTokensAfter", 34443, "summaryVersionAfter", 1));

            var persisted = store.find(owner, run).stream()
                    .filter(entry -> "COMPACTION".equals(entry.kind())).findFirst();
            assertThat(persisted).isPresent();
            assertThat(persisted.orElseThrow().name()).isEqualTo("context.compact.1");
            assertThat(persisted.orElseThrow().attributes()).contains(
                    Map.entry("softThresholdTokens", 43622),
                    Map.entry("projectedInputTokens", 57432),
                    Map.entry("outcome", "COMPACTED"),
                    Map.entry("estimatedInputTokensAfter", 34443),
                    Map.entry("summaryVersionAfter", 1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_trace_spans WHERE run_id = ? AND kind = 'LLM'",
                    Integer.class, run)).isZero();

            // The widened constraint still guards against kinds nobody declared.
            assertThat(store.startSpan(owner, run, rootSpan, "NOT_A_KIND", "agent.bogus", Map.of())).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_trace_spans WHERE run_id = ? AND kind = 'NOT_A_KIND'",
                    Integer.class, run)).isZero();
        } finally {
            root.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}

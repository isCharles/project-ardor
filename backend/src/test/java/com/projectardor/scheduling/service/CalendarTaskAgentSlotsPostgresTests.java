package com.projectardor.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.projectardor.scheduling.domain.CalendarTaskPriority;

/** Real PostgreSQL predicates on a connection-local table; no production rows are touched. */
@EnabledIfEnvironmentVariable(named="ARDOR_ACCEPTANCE_DISPOSABLE_DB",matches="true")
class CalendarTaskAgentSlotsPostgresTests {
    private static final Instant DUE = Instant.parse("2028-04-01T01:00:00Z");
    private SingleConnectionDataSource dataSource;
    private CalendarTaskAgentSlots slots;
    private final UUID user = UUID.randomUUID(), action = UUID.randomUUID();

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var connection = DriverManager.getConnection(url, "ardor", "acceptance-only");
        dataSource = new SingleConnectionDataSource(connection, true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        slots = new CalendarTaskAgentSlots(jdbc);
        jdbc.execute("""
                CREATE TEMP TABLE agent_calendar_create_slots(
                    request_id uuid PRIMARY KEY, user_id uuid, logical_action_id uuid,
                    semantic_key char(64), call_signature char(64), origin_run_id uuid,
                    task_id uuid, created_at timestamptz DEFAULT CURRENT_TIMESTAMP)
                """);
    }
    @AfterEach void close() { if (dataSource != null) dataSource.destroy(); }

    @Test void aCommittedSemanticActionIsReturnedAcrossRuns() {
        UUID task = UUID.randomUUID();
        String semantic = CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null);
        assertThat(slots.existing(user, action, UUID.randomUUID(), semantic)).isEmpty();
        slots.record(user, action, UUID.randomUUID(), semantic, CalendarTaskAgentSlots.callSignature("desc"), task);
        assertThat(slots.existing(user, action, UUID.randomUUID(), semantic)).contains(task);
    }

    @Test void aDifferentSemanticActionFromAnotherRunIsRejected() {
        String first = CalendarTaskAgentSlots.semanticKey("Review", DUE, CalendarTaskPriority.HIGH, null);
        String changed = CalendarTaskAgentSlots.semanticKey("Prepare", DUE, CalendarTaskPriority.HIGH, null);
        slots.record(user, action, UUID.randomUUID(), first, CalendarTaskAgentSlots.callSignature("desc"), UUID.randomUUID());
        assertThatThrownBy(() -> slots.existing(user, action, UUID.randomUUID(), changed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不同的日程");
    }
}

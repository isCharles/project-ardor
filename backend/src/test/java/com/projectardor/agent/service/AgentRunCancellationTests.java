package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import jakarta.persistence.EntityManager;

import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.domain.ConversationMessage;
import com.projectardor.common.web.ResourceNotFoundException;

/**
 * Real PostgreSQL cancellation contract, isolated to a connection-local {@code agent_runs} table.
 * No production rows or shared schema are read or changed; the connection is closed after each test.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentRunCancellationTests {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentConversationStore conversations;
    private AgentRunStore runs;
    private final UUID owner = UUID.randomUUID();
    private final UUID otherOwner = UUID.randomUUID();

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        // Dedicated acceptance credentials only. Do not consume arbitrary application credentials.
        var connection = DriverManager.getConnection(url, "ardor", "acceptance-only");
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        conversations = mock(AgentConversationStore.class);
        runs = new AgentRunStore(jdbc, conversations, mock(EntityManager.class));
        jdbc.execute("""
                CREATE TEMP TABLE agent_runs (
                  id uuid PRIMARY KEY,
                  user_id uuid NOT NULL,
                  conversation_id uuid NOT NULL,
                  request_hash char(64) NOT NULL DEFAULT repeat('0', 64),
                  message text NOT NULL DEFAULT 'fixture',
                  status varchar(24) NOT NULL DEFAULT 'RUNNING',
                  label varchar(240) NOT NULL DEFAULT '正在理解你的请求',
                  tool_started boolean NOT NULL DEFAULT false,
                  error_code varchar(80),
                  error_message text,
                  retryable boolean NOT NULL DEFAULT false,
                  assistant_message_id uuid,
                  logical_action_id uuid,
                  context_references jsonb NOT NULL DEFAULT '[]'::jsonb,
                  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  finished_at timestamptz
                )
                """);
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.destroy();
    }

    private void insert(UUID id, UUID userId, String status, String errorCode) {
        jdbc.update("""
                INSERT INTO agent_runs(id, user_id, conversation_id, message, status, error_code)
                VALUES(?, ?, ?, '合成消息', ?, ?)
                """, id, userId, UUID.randomUUID(), status, errorCode);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?", String.class, id);
    }

    @Test
    void terminalRunCancelIsIdempotentAndReportsItsOwnState() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "COMPLETED", null);

        var first = runs.cancel(owner, id, false);
        var second = runs.cancel(owner, id, false);

        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM agent_runs WHERE id = ?",
                java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    void runningRunWithoutLocalExecutorBecomesInterruptedWithoutClaimingSuccess() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "RUNNING", null);

        var cancelled = runs.cancel(owner, id, false);

        assertThat(cancelled.status()).isEqualTo("INTERRUPTED");
        assertThat(cancelled.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(cancelled.retryable()).isFalse();
        assertThat(cancelled.errorMessage()).contains("无法确认");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM agent_runs WHERE id = ?",
                java.sql.Timestamp.class, id)).isNotNull();
        // Repeated cancellation is idempotent and does not invent a different terminal state.
        var repeated = runs.cancel(owner, id, false);
        assertThat(repeated.status()).isEqualTo("INTERRUPTED");
        assertThat(repeated.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(status(id)).isEqualTo("INTERRUPTED");
    }

    @Test
    void runningRunOwnedByLocalExecutorIsLeftToTheTerminateHook() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "RUNNING", null);

        var run = runs.cancel(owner, id, true);

        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM agent_runs WHERE id = ?",
                java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    void lateReplyCannotOverwriteAnExplicitlyCancelledInterruptedRun() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "RUNNING", null);
        runs.cancel(owner, id, false);

        assertThatThrownBy(() -> runs.complete(owner, id, Conversation.create(owner, "合成会话"),
                "合成消息", "迟到的回复", null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本轮已停止");
        assertThat(status(id)).isEqualTo("INTERRUPTED");
    }

    @Test
    void uncertainStaleInterruptionStillAcceptsALateReply() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "INTERRUPTED", "AGENT_RUN_INTERRUPTED");
        Conversation conversation = Conversation.create(owner, "合成会话");
        List<ConversationMessage> saved = List.of(
                ConversationMessage.user(owner, conversation.getId(), "合成消息", List.of()),
                ConversationMessage.assistant(owner, conversation.getId(), "迟到的回复", null));
        when(conversations.appendExchange(any(), any(), anyString(), anyString(), any(), any())).thenReturn(saved);

        // Only an explicit cancel is final. A stale "unconfirmed" interruption stays completable so a
        // slow but live run can still save its real reply.
        assertThatCode(() -> runs.complete(owner, id, conversation, "合成消息", "迟到的回复", null, List.of()))
                .doesNotThrowAnyException();
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    @Test
    void cancelIsOwnerScopedAndUnknownRunsAreNotLeaked() {
        UUID id = UUID.randomUUID();
        insert(id, owner, "RUNNING", null);

        assertThatThrownBy(() -> runs.cancel(otherOwner, id, false))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThatThrownBy(() -> runs.cancel(owner, UUID.randomUUID(), false))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

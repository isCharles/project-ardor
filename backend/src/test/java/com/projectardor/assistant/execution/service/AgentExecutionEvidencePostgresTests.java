package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;

import com.projectardor.assistant.conversation.domain.Conversation;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL evidence for the durable run/trace record and its transactional completion boundary.
 * Each test migrates a unique throwaway schema; only JPA message persistence and failure injection are
 * mocked, while every assertion about persisted execution state reads JDBC rows.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentExecutionEvidencePostgresTests {

    private static final String PROMPT = "请总结本轮执行证据";
    private static final String ANSWER = "已保存可核验的执行证据";
    private static final String CANCEL_MESSAGE =
            "已停止本轮；当前服务无法确认远端模型执行是否已终止，已开始的操作请核验结果。";

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private String schema;
    private Conversation conversation;
    private AgentConversationStore conversations;
    private EntityManager entityManager;
    private AgentExecutionAuditStore audit;
    private AgentTraceStore traces;
    private AgentRunStore runs;

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        schema = "ardor_exec_evidence_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        root.execute("CREATE SCHEMA " + schema);
        try {
            String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
            dataSource = new SingleConnectionDataSource(scopedUrl, "ardor", "acceptance-only", true);
            jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            for (UUID user : List.of(owner, stranger)) {
                jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                        user, user + "@ardor.invalid", "synthetic-only");
            }
            conversation = Conversation.create(owner, "合成执行证据会话");
            jdbc.update("INSERT INTO conversations(id,user_id,title) VALUES(?,?,?)",
                    conversation.getId(), owner, conversation.getTitle());
            conversations = mock(AgentConversationStore.class);
            entityManager = mock(EntityManager.class);
            audit = mock(AgentExecutionAuditStore.class);
            traces = new AgentTraceStore(jdbc, JsonMapper.builder().build(), null, true, 30);
            runs = runStore(audit);
        } catch (RuntimeException failure) {
            root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            throw failure;
        }
    }

    @AfterEach
    void cleanup() {
        try {
            if (jdbc != null) {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        } finally {
            if (dataSource != null) dataSource.destroy();
        }
    }

    @Test
    void runFactsAndTraceSpansAreRealRowsAndRemainOwnerScoped() {
        UUID run = beginRun();
        assertThat(runs.get(owner, run)).satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(run);
            assertThat(stored.conversationId()).isEqualTo(conversation.getId());
            assertThat(stored.message()).isEqualTo(PROMPT);
            assertThat(stored.status()).isEqualTo("RUNNING");
        });
        assertThatThrownBy(() -> runs.get(stranger, run)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(runs.facts(stranger, run)).isNull();

        UUID span = traces.startSpan(owner, run, null, "LLM", "agent.evidence", Map.of("round", 1));
        assertThat(span).isNotNull();
        traces.finishSpan(owner, span, "COMPLETED", null, "synthetic-model", "evidence-v1",
                11, 7, 3, 2, Map.of("verified", true));
        traces.updateRunAggregates(owner, run, 1, 2, 3, 11, 7, 3, 2,
                true, "synthetic-model", "evidence-v1");
        traces.finishSpan(stranger, span, "FAILED", "FOREIGN_OWNER", "other-model", "other",
                99, 99, 99, 99, Map.of());
        traces.updateRunAggregates(stranger, run, 9, 9, 9, 99, 99, 99, 99,
                true, "other-model", "other");

        assertThat(runs.facts(owner, run)).satisfies(facts -> {
            assertThat(facts.runId()).isEqualTo(run);
            assertThat(facts.status()).isEqualTo("RUNNING");
            assertThat(facts.finishedAt()).isNull();
            assertThat(facts.llmCalls()).isEqualTo(1);
            assertThat(facts.toolCalls()).isEqualTo(2);
            assertThat(facts.loopRounds()).isEqualTo(3);
            assertThat(facts.inputTokens()).isEqualTo(11);
            assertThat(facts.outputTokens()).isEqualTo(7);
            assertThat(facts.cacheCreationInputTokens()).isEqualTo(3);
            assertThat(facts.cacheReadInputTokens()).isEqualTo(2);
        });
        assertThat(traces.find(stranger, run)).isEmpty();
        assertThat(traces.find(owner, run)).singleElement().satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(span);
            assertThat(stored.status()).isEqualTo("COMPLETED");
            assertThat(stored.errorCode()).isNull();
            assertThat(stored.model()).isEqualTo("synthetic-model");
            assertThat(stored.finishedAt()).isNotNull();
            assertThat(stored.attributes()).containsEntry("round", 1).containsEntry("verified", true);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_runs WHERE id=?", Integer.class, run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_trace_spans WHERE id=?", Integer.class, span)).isEqualTo(1);
    }

    @Test
    void completeCommitsTheRunAssistantLinkAndExchangeThroughTheTransactionProxy() {
        UUID run = beginRun();
        runs = runStore(new AgentExecutionAuditStore(jdbc));
        stubExchange();

        List<ConversationMessage> saved = runs.complete(owner, run, conversation, PROMPT, ANSWER, null, List.of());

        assertThat(saved).hasSize(2);
        assertThat(runs.get(owner, run)).satisfies(stored -> {
            assertThat(stored.status()).isEqualTo("COMPLETED");
            assertThat(stored.assistantMessageId()).isEqualTo(saved.get(1).getId());
            assertThat(stored.errorCode()).isNull();
        });
        assertThat(runs.facts(owner, run).finishedAt()).isNotNull();
        for (ConversationMessage message : saved) {
            assertThat(message.getRunId()).isEqualTo(run);
            assertThat(message.getRequestId()).isEqualTo(run);
            assertThat(jdbc.queryForObject("SELECT run_id FROM messages WHERE id=?", UUID.class, message.getId()))
                    .isEqualTo(run);
            assertThat(jdbc.queryForObject("SELECT request_id FROM messages WHERE id=?", UUID.class, message.getId()))
                    .isEqualTo(run);
        }
        assertThat(jdbc.queryForObject("SELECT assistant_message_id FROM agent_runs WHERE id=?",
                UUID.class, run)).isEqualTo(saved.get(1).getId());
        assertThatThrownBy(() -> runs.get(stranger, run)).isInstanceOf(ResourceNotFoundException.class);
        verify(entityManager).flush();
    }

    @Test
    void auditLinkFailureRollsBackTheRealRunUpdateAndInsertedExchange() {
        UUID run = beginRun();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=?", run);
        stubExchange();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("audit link failed");
        }).when(audit).linkRunExchange(eq(owner), eq(run), any(UUID.class), any(UUID.class));

        assertThatThrownBy(() -> runs.complete(owner, run, conversation, PROMPT, ANSWER, null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit link failed");

        assertThat(jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=?", run)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?",
                Integer.class, conversation.getId())).isZero();
        assertThat(runs.get(owner, run).status()).isEqualTo("RUNNING");
        verify(entityManager).flush();
    }

    @Test
    void cancelWithoutLocalExecutorIsTerminalAndRejectsALateReply() {
        UUID run = beginRun();
        assertThatThrownBy(() -> runs.cancel(stranger, run, false)).isInstanceOf(ResourceNotFoundException.class);

        AgentRunStore.AgentRun cancelled = runs.cancel(owner, run, false);

        assertThat(cancelled.status()).isEqualTo("INTERRUPTED");
        assertThat(cancelled.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(cancelled.label()).isEqualTo("状态待确认");
        assertThat(cancelled.errorMessage()).isEqualTo(CANCEL_MESSAGE);
        assertThat(cancelled.retryable()).isFalse();
        assertThat(jdbc.queryForMap("SELECT status,error_code,error_message,retryable,finished_at FROM agent_runs WHERE id=?", run))
                .containsEntry("status", "INTERRUPTED")
                .containsEntry("error_code", "AGENT_RUN_CANCELLED")
                .containsEntry("error_message", CANCEL_MESSAGE)
                .containsEntry("retryable", false)
                .containsKey("finished_at");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM agent_runs WHERE id=?", Timestamp.class, run)).isNotNull();
        verify(audit).finishInterruptedRun(owner, run, "AGENT_RUN_CANCELLED");
        Map<String, Object> terminal = jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=?", run);

        assertThatThrownBy(() -> runs.complete(owner, run, conversation, PROMPT, "迟到的回复", null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本轮已停止，不能保存迟到的回复");
        assertThat(jdbc.queryForMap("SELECT * FROM agent_runs WHERE id=?", run)).isEqualTo(terminal);
        verifyNoInteractions(conversations, entityManager);
    }

    private UUID beginRun() {
        UUID run = UUID.randomUUID();
        assertThat(runs.begin(owner, run, conversation.getId(), PROMPT, null, null, List.of())).isTrue();
        return run;
    }

    /** The mocked JPA boundary inserts the IDs it returns, so JDBC rollback also covers message rows. */
    private void stubExchange() {
        when(conversations.appendExchange(eq(owner), eq(conversation), eq(PROMPT), eq(ANSWER),
                isNull(), anyList(), eq(false), anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            List<ConversationMessage> saved = List.of(
                    ConversationMessage.user(owner, conversation.getId(), PROMPT, List.of()),
                    ConversationMessage.assistant(owner, conversation.getId(), ANSWER, null));
            for (ConversationMessage message : saved) {
                jdbc.update("""
                        INSERT INTO messages(id,user_id,conversation_id,role,content,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?)
                        """, message.getId(), owner, conversation.getId(), message.getRole(), message.getContent(),
                        Timestamp.from(java.time.Instant.now()), Timestamp.from(java.time.Instant.now()));
            }
            return saved;
        });
    }

    private AgentRunStore runStore(AgentExecutionAuditStore executionAudit) {
        var target = new AgentRunStore(jdbc, conversations, entityManager, executionAudit);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (AgentRunStore) factory.getProxy();
    }
}

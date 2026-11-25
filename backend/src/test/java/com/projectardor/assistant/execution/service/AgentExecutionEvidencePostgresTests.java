package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import com.projectardor.assistant.conversation.domain.Conversation;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.repository.ConversationMessageRepository;
import com.projectardor.assistant.conversation.repository.ConversationRepository;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real PostgreSQL evidence for the durable run/trace record and its transactional completion boundary.
 * Each test migrates a unique throwaway schema and runs real JPA message persistence in the same
 * transaction as the JDBC run update; assertions about committed or rolled-back state read JDBC rows.
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
    private JdbcTemplate assertions;
    private String schema;
    private Conversation conversation;
    private AgentConversationStore conversations;
    private EntityManager entityManager;
    private EntityManagerFactory entityManagerFactory;
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
            assertions = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, "ardor", "acceptance-only"));
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            for (UUID user : List.of(owner, stranger)) {
                jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)",
                        user, user + "@ardor.invalid", "synthetic-only");
            }
            conversation = Conversation.create(owner, "合成执行证据会话");
            jdbc.update("INSERT INTO conversations(id,user_id,title) VALUES(?,?,?)",
                    conversation.getId(), owner, conversation.getTitle());

            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.projectardor.assistant.conversation.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            factory.afterPropertiesSet();
            entityManagerFactory = factory.getObject();
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            var repositories = new JpaRepositoryFactory(entityManager);
            var conversationRepository = repositories.getRepository(ConversationRepository.class);
            conversation = conversationRepository.findByIdAndUserId(conversation.getId(), owner).orElseThrow();
            conversations = new AgentConversationStore(
                    conversationRepository,
                    repositories.getRepository(ConversationMessageRepository.class),
                    mock(ApplicationEventPublisher.class), null);
            audit = spy(new AgentExecutionAuditStore(jdbc));
            traces = new AgentTraceStore(jdbc, JsonMapper.builder().build(), null, true, 30);
            runs = runStore(audit);
        } catch (Exception failure) {
            root.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            throw failure;
        }
    }

    @AfterEach
    void cleanup() {
        try {
            if (entityManagerFactory != null) entityManagerFactory.close();
            if (assertions != null) {
                assertions.execute("SET search_path TO public");
                assertions.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
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

        List<ConversationMessage> saved = runs.complete(owner, run, conversation, PROMPT, ANSWER, null, List.of());

        assertThat(saved).hasSize(2);
        assertThat(assertions.queryForList("SELECT role FROM messages WHERE conversation_id=? ORDER BY created_at,id",
                String.class, conversation.getId())).containsExactlyInAnyOrder("USER", "ASSISTANT");
        assertThat(assertions.queryForList("SELECT content FROM messages WHERE conversation_id=? ORDER BY created_at,id",
                String.class, conversation.getId())).containsExactlyInAnyOrder(PROMPT, ANSWER);
        assertThat(runs.get(owner, run)).satisfies(stored -> {
            assertThat(stored.status()).isEqualTo("COMPLETED");
            assertThat(stored.assistantMessageId()).isEqualTo(saved.get(1).getId());
            assertThat(stored.errorCode()).isNull();
        });
        assertThat(runs.facts(owner, run).finishedAt()).isNotNull();
        for (ConversationMessage message : saved) {
            assertThat(message.getRunId()).isEqualTo(run);
            assertThat(message.getRequestId()).isEqualTo(run);
            assertThat(assertions.queryForObject("SELECT run_id FROM messages WHERE id=?", UUID.class, message.getId()))
                    .isEqualTo(run);
            assertThat(assertions.queryForObject("SELECT request_id FROM messages WHERE id=?", UUID.class, message.getId()))
                    .isEqualTo(run);
        }
        assertThat(assertions.queryForObject("SELECT assistant_message_id FROM agent_runs WHERE id=?",
                UUID.class, run)).isEqualTo(saved.get(1).getId());
        assertThatThrownBy(() -> runs.get(stranger, run)).isInstanceOf(ResourceNotFoundException.class);
        verify(audit).linkRunExchange(owner, run, saved.get(0).getId(), saved.get(1).getId());
    }

    @Test
    void auditLinkFailureRollsBackTheRealRunUpdateAndInsertedExchange() {
        UUID run = beginRun();
        Map<String, Object> before = assertions.queryForMap("SELECT * FROM agent_runs WHERE id=?", run);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            invocation.callRealMethod();
            UUID userMessage = invocation.getArgument(2, UUID.class);
            UUID assistantMessage = invocation.getArgument(3, UUID.class);
            assertThat(jdbc.queryForObject("SELECT status FROM agent_runs WHERE id=?", String.class, run))
                    .isEqualTo("COMPLETED");
            for (UUID message : List.of(userMessage, assistantMessage)) {
                assertThat(jdbc.queryForObject("SELECT run_id FROM messages WHERE id=?", UUID.class, message))
                        .isEqualTo(run);
            }
            throw new IllegalStateException("audit link failed");
        }).when(audit).linkRunExchange(eq(owner), eq(run), any(UUID.class), any(UUID.class));

        assertThatThrownBy(() -> runs.complete(owner, run, conversation, PROMPT, ANSWER, null, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit link failed");

        assertThat(assertions.queryForMap("SELECT * FROM agent_runs WHERE id=?", run)).isEqualTo(before);
        assertThat(assertions.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?",
                Integer.class, conversation.getId())).isZero();
        assertThat(runs.get(owner, run).status()).isEqualTo("RUNNING");
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
        assertThat(assertions.queryForMap("SELECT * FROM agent_runs WHERE id=?", run)).isEqualTo(terminal);
        assertThat(assertions.queryForObject("SELECT count(*) FROM messages WHERE conversation_id=?",
                Integer.class, conversation.getId())).isZero();
    }

    private UUID beginRun() {
        UUID run = UUID.randomUUID();
        assertThat(runs.begin(owner, run, conversation.getId(), PROMPT, null, null, List.of())).isTrue();
        return run;
    }

    private AgentRunStore runStore(AgentExecutionAuditStore executionAudit) {
        var target = new AgentRunStore(jdbc, conversations, entityManager, executionAudit);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new JpaTransactionManager(entityManagerFactory));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (AgentRunStore) factory.getProxy();
    }
}

package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import jakarta.persistence.EntityManager;

import com.projectardor.assistant.conversation.domain.Conversation;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.assistant.conversation.service.AgentConversationStore;

/**
 * Real PostgreSQL contract for V53: message/run backfill, structured tool calls, proposal
 * lifecycle and truthful interruption attribution.
 *
 * <p>Each test creates its own throwaway schema on an isolated acceptance database, builds the
 * pre-V53 shape, inserts fixtures, then executes the real migration file. Nothing is read or
 * written in the shared {@code public} schema, and the schema is dropped afterwards.
 */
@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class AgentExecutionAuditPostgresTests {

    private static final String V53 = "db/migration/V53__link_agent_execution_and_proposal_audit.sql";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private String schema;
    private AgentConversationStore conversations;
    private AgentExecutionAuditStore audit;
    private AgentRunStore runs;

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL",
                "jdbc:postgresql://127.0.0.1:5432/ardor");
        // Dedicated acceptance credentials only. Do not consume arbitrary application credentials.
        var connection = DriverManager.getConnection(url, "ardor", "acceptance-only");
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        schema = "ardor_e2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("SET search_path TO " + schema);
        for (String statement : statements(PRE_V53_SCHEMA)) jdbc.execute(statement);
        insertUser(owner);
        insertUser(stranger);
        audit = new AgentExecutionAuditStore(jdbc);
        conversations = mock(AgentConversationStore.class);
        runs = new AgentRunStore(jdbc, conversations, mock(EntityManager.class), audit);
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

    // ---- V53 backfill -----------------------------------------------------------------

    @Test
    void backfillsOnlyExactHistoricalLinksAndKeepsAmbiguousHistoryNull() {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        insertConversation(conversation, owner);
        insertMessage(userMessage, owner, conversation, "USER", "请分析我的简历", base);
        insertMessage(assistantMessage, owner, conversation, "ASSISTANT", "好的，已分析", base.plusSeconds(1));
        UUID run = UUID.randomUUID();
        insertRun(run, owner, conversation, "COMPLETED", "请分析我的简历", assistantMessage, null, base.plusMillis(500), base.plusSeconds(2));

        // Two runs claim the same assistant message: the link is ambiguous and must stay NULL.
        UUID ambiguousUser = UUID.randomUUID();
        UUID ambiguousAssistant = UUID.randomUUID();
        insertMessage(ambiguousUser, owner, conversation, "USER", "第二条消息", base.plusSeconds(10));
        insertMessage(ambiguousAssistant, owner, conversation, "ASSISTANT", "两条运行都指向我", base.plusSeconds(11));
        insertRun(UUID.randomUUID(), owner, conversation, "COMPLETED", "第二条消息", ambiguousAssistant, null,
                base.plusSeconds(9), base.plusSeconds(12));
        insertRun(UUID.randomUUID(), owner, conversation, "COMPLETED", "第二条消息", ambiguousAssistant, null,
                base.plusSeconds(13), base.plusSeconds(14));
        // A legacy message that no run ever linked can never be parsed into a run identity.
        UUID orphan = UUID.randomUUID();
        insertMessage(orphan, owner, conversation, "USER", "没有持久化运行的历史消息", base.plusSeconds(20));

        applyV53();

        assertThat(runOf(userMessage)).isEqualTo(run);
        assertThat(requestOf(userMessage)).isEqualTo(run);
        assertThat(runOf(assistantMessage)).isEqualTo(run);
        assertThat(requestOf(assistantMessage)).isEqualTo(run);
        assertThat(runOf(ambiguousUser)).isNull();
        assertThat(runOf(ambiguousAssistant)).isNull();
        assertThat(runOf(orphan)).isNull();
        assertThat(requestOf(orphan)).isNull();
    }

    // ---- structured tool calls --------------------------------------------------------

    @Test
    void recordsToolCallOwnershipApprovalSourceAndTruthfulStatuses() {
        applyV53();
        UUID run = runningRun(owner, conversation);
        var context = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, run);

        UUID read = audit.startToolCall(context, "call-read", "list_resumes", "{}", "AUTO");
        UUID businessResult = UUID.randomUUID();
        audit.finishToolCall(read, owner, "COMPLETED", "{\"resumeId\":\"" + businessResult + "\"}", null);

        UUID write = audit.startToolCall(context, "call-write", "update_memory", "{\"memory\":\"私有内容\"}", "CONFIRMED");
        audit.finishToolCall(write, owner, "FAILED", null, "TOOL_FAILED");

        UUID cancelled = audit.startToolCall(context, "call-cancelled", "search_web", "{\"query\":\"x\"}", "AUTO");

        UUID refused = audit.startToolCall(context, "call-refused", "update_memory_card", "{}", "CONFIRMED");
        audit.finishToolCall(refused, owner, "NOT_EXECUTED", null, "NOT_CONFIRMED");

        audit.finishInterruptedRun(owner, run, "AGENT_RUN_DEADLINE");

        List<AgentExecutionAuditStore.ToolCall> calls = audit.toolCalls(owner, run);
        assertThat(calls).hasSize(4);
        AgentExecutionAuditStore.ToolCall readCall = calls.stream()
                .filter(call -> "call-read".equals(call.toolCallId())).findFirst().orElseThrow();
        assertThat(readCall.status()).isEqualTo("COMPLETED");
        assertThat(readCall.approvalSource()).isEqualTo("AUTO");
        assertThat(readCall.module()).isEqualTo("resumes");
        assertThat(readCall.capability()).isEqualTo("READ");
        assertThat(readCall.resultRef()).isEqualTo(businessResult.toString());
        assertThat(readCall.argumentsDigest()).hasSize(64).doesNotContain("{}");
        assertThat(readCall.startedAt()).isNotNull();
        assertThat(readCall.finishedAt()).isNotNull();

        assertThat(statusOf(calls, "call-write")).isEqualTo("FAILED");
        assertThat(errorOf(calls, "call-write")).isEqualTo("TOOL_FAILED");
        assertThat(statusOf(calls, "call-refused")).isEqualTo("NOT_EXECUTED");
        // The started call that never reported a result is UNKNOWN, never a fabricated success.
        assertThat(statusOf(calls, "call-cancelled")).isEqualTo("UNKNOWN");
        assertThat(errorOf(calls, "call-cancelled")).isEqualTo("AGENT_RUN_DEADLINE");

        // Arguments are not copied into the audit table.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_calls WHERE arguments_digest LIKE '%私有内容%'", Integer.class)).isZero();
        // Owner isolation for the read path.
        assertThat(audit.toolCalls(stranger, run)).isEmpty();
    }

    @Test
    void compositeForeignKeysRejectCrossUserAttribution() {
        applyV53();
        UUID run = runningRun(owner, conversation);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_tool_calls(id, user_id, conversation_id, run_id, tool_name,
                    approval_source, arguments_digest, status)
                VALUES (?, ?, ?, ?, 'search_web', 'AUTO', repeat('0', 64), 'STARTED')
                """, UUID.randomUUID(), stranger, conversation, run))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_proposals(id, user_id, conversation_id, run_id, kind, legacy_key, status)
                VALUES (?, ?, ?, ?, 'resume', 'resume:/x', 'PROPOSED')
                """, UUID.randomUUID(), stranger, conversation, run))
                .isInstanceOf(DataAccessException.class);
    }

    // ---- proposals --------------------------------------------------------------------

    @Test
    void proposalLifecycleKeepsFirstDecisionAndCancelsOpenCards() {
        applyV53();
        UUID run = runningRun(owner, conversation);
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        insertMessage(userMessage, owner, conversation, "USER", "帮我记住", Instant.now());
        insertMessage(assistantMessage, owner, conversation, "ASSISTANT", "已记录", Instant.now().plusSeconds(1));
        var context = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, run);

        UUID first = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(first, context, "memory_item",
                UUID.randomUUID(), null, "memory_item:/api/agent/memory/items/one", "一条记忆",
                "从长期记忆中删除这一条", "/api/agent/memory/items/one", null));
        assertThat(audit.proposals(owner, run)).singleElement()
                .satisfies(proposal -> {
                    assertThat(proposal.id()).isEqualTo(first);
                    assertThat(proposal.status()).isEqualTo("PROPOSED");
                    assertThat(proposal.messageId()).isNull();
                });

        audit.linkRunExchange(owner, run, userMessage, assistantMessage);
        assertThat(runOf(userMessage)).isEqualTo(run);
        assertThat(runOf(assistantMessage)).isEqualTo(run);
        assertThat(audit.proposals(owner, run).getFirst().messageId()).isEqualTo(assistantMessage);

        assertThat(audit.decideProposal(owner, assistantMessage, first, "memory_item:/api/agent/memory/items/one", "DONE")).isTrue();
        AgentExecutionAuditStore.Proposal decided = audit.proposals(owner, run).getFirst();
        assertThat(decided.status()).isEqualTo("CONFIRMED");
        assertThat(decided.resolution()).isEqualTo("DONE");
        assertThat(decided.resolvedAt()).isNotNull();
        // A late different decision cannot overwrite the first one.
        assertThat(audit.decideProposal(owner, assistantMessage, first, "memory_item:/api/agent/memory/items/one", "DISMISSED")).isFalse();
        assertThat(audit.proposals(owner, run).getFirst().status()).isEqualTo("CONFIRMED");

        UUID expired = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(expired, context, "calendar_task",
                UUID.randomUUID(), null, "calendar_task:/api/calendar/tasks/two", "待办",
                null, "/api/calendar/tasks/two", null));
        assertThat(audit.decideProposal(owner, null, expired, "calendar_task:/api/calendar/tasks/two", "EXPIRED")).isTrue();
        assertThat(audit.proposals(owner, run).stream().filter(item -> item.id().equals(expired)).findFirst().orElseThrow()
                .status()).isEqualTo("EXPIRED");

        UUID open = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(open, context, "resume",
                null, List.of(UUID.randomUUID()), "resume:/api/resumes/three", "全部记忆",
                null, "/api/resumes/three", null));
        audit.finishInterruptedRun(owner, run, "AGENT_RUN_CANCELLED");
        assertThat(audit.proposals(owner, run).stream().filter(item -> item.id().equals(open)).findFirst().orElseThrow()
                .status()).isEqualTo("CANCELLED");
        // The already confirmed decision is never overwritten by a later cancellation.
        assertThat(audit.proposals(owner, run).stream().filter(item -> item.id().equals(first)).findFirst().orElseThrow()
                .status()).isEqualTo("CONFIRMED");

        assertThat(audit.proposals(stranger, run)).isEmpty();
    }

    @Test
    void legacyAuditKeepsDigestsAndAddsApprovalSource() {
        applyV53();
        UUID run = runningRun(owner, conversation);
        audit.recordAudit(owner, run, "update_memory", "{\"memory\":\"敏感正文\"}", "CONFIRMED");
        audit.recordAudit(owner, run, "delete_resume", "{\"resumeId\":\"x\"}", "DELETE_PROPOSAL");

        List<AgentExecutionAuditStore.AuditEvent> events = audit.auditEvents(owner, run);
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(AgentExecutionAuditStore.AuditEvent::approvalSource))
                .containsExactlyInAnyOrder("CONFIRMED", "DELETE_PROPOSAL");
        assertThat(events.getFirst().argumentsDigest()).hasSize(64).doesNotContain("敏感正文");
        assertThat(audit.auditEvents(stranger, run)).isEmpty();
        // Old auto-approval rows keep their historical meaning through the column default.
        jdbc.update("""
                INSERT INTO agent_tool_audit(id, user_id, run_id, tool, module, capability, arguments_digest)
                VALUES (?, ?, ?, 'add_memory', 'memory', 'CREATE', repeat('0', 64))
                """, UUID.randomUUID(), owner, run);
        assertThat(jdbc.queryForObject("""
                SELECT approval_source FROM agent_tool_audit
                WHERE user_id = ? AND run_id = ? AND tool = 'add_memory'
                """, String.class, owner, run)).isEqualTo("AUTO");
    }

    // ---- run -> message -> tool -> proposal -> result ----------------------------------

    @Test
    void completedRunPersistsTheAssistantMessageAndLinksStructuredRows() {
        applyV53();
        UUID run = runningRun(owner, conversation);
        var context = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, run);
        UUID call = audit.startToolCall(context, "call-plan", "list_learning_plans", "{}", "AUTO");
        UUID proposal = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(proposal, context, "learning_plan",
                null, null, "learning-request-id", "JVM", null, "/api/learning-plans/jobs", null));

        Conversation domainConversation = Conversation.create(owner, "合成会话");
        List<ConversationMessage> saved = List.of(
                ConversationMessage.user(owner, domainConversation.getId(), "帮我安排学习计划", List.of()),
                ConversationMessage.assistant(owner, domainConversation.getId(), "建议如下", null));
        // The mocked conversation store does not persist rows; provide the flushed rows the real JPA
        // flush would have produced so the owner-scoped assistant-message FK can be verified.
        insertConversation(domainConversation.getId(), owner);
        insertMessage(saved.get(0).getId(), owner, domainConversation.getId(), "USER", "帮我安排学习计划", java.time.Instant.now());
        insertMessage(saved.get(1).getId(), owner, domainConversation.getId(), "ASSISTANT", "建议如下", java.time.Instant.now());
        org.mockito.Mockito.when(conversations.appendExchange(org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.any(Conversation.class),
                org.mockito.ArgumentMatchers.eq("帮我安排学习计划"),
                org.mockito.ArgumentMatchers.eq("建议如下"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        List<ConversationMessage> completed = runs.complete(owner, run, domainConversation,
                "帮我安排学习计划", "建议如下", null, List.of());

        assertThat(completed).hasSize(2);
        // The saved assistant message identity is the run's message link, and both messages carry it.
        assertThat(runs.get(owner, run).status()).isEqualTo("COMPLETED");
        assertThat(runs.get(owner, run).assistantMessageId()).isEqualTo(saved.get(1).getId());
        assertThat(saved.getFirst().getRunId()).isEqualTo(run);
        assertThat(saved.get(1).getRequestId()).isEqualTo(run);
        assertThat(jdbc.queryForObject("SELECT run_id FROM messages WHERE id = ?", UUID.class, saved.get(1).getId()))
                .isEqualTo(run);
        // Structured rows are linked to the assistant message in the same transaction.
        assertThat(jdbc.queryForObject("SELECT message_id FROM agent_tool_calls WHERE id = ?", UUID.class, call))
                .isEqualTo(saved.get(1).getId());
        assertThat(jdbc.queryForObject("SELECT message_id FROM agent_proposals WHERE id = ?", UUID.class, proposal))
                .isEqualTo(saved.get(1).getId());
    }

    @Test
    void runMessageToolProposalResultChainIsQueryableAndOwnerScoped() {
        Instant base = Instant.parse("2026-10-01T00:00:00Z");
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        insertConversation(conversation, owner);
        insertMessage(userMessage, owner, conversation, "USER", "帮我安排学习计划", base);
        insertMessage(assistantMessage, owner, conversation, "ASSISTANT", "建议如下", base.plusSeconds(1));
        UUID run = UUID.randomUUID();
        insertRun(run, owner, conversation, "COMPLETED", "帮我安排学习计划", assistantMessage, null,
                base.plusMillis(500), base.plusSeconds(2));
        applyV53();

        var context = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, run);
        UUID call = audit.startToolCall(context, "call-plan", "list_learning_plans", "{}", "AUTO");
        UUID resultResource = UUID.randomUUID();
        audit.finishToolCall(call, owner, "COMPLETED", "{\"planId\":\"" + resultResource + "\"}", null);
        UUID proposal = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(proposal, context, "learning_plan",
                null, null, "learning-request-id", "JVM", "建议学习", "/api/learning-plans/jobs",
                UUID.randomUUID()));
        audit.linkRunExchange(owner, run, userMessage, assistantMessage);

        var joined = jdbc.queryForMap("""
                SELECT r.id AS run_id, um.id AS user_message_id, am.id AS assistant_message_id,
                       tc.result_ref, p.id AS proposal_id, p.message_id AS proposal_message_id
                FROM agent_runs r
                JOIN messages um ON um.run_id = r.id AND um.role = 'USER'
                JOIN messages am ON am.run_id = r.id AND am.role = 'ASSISTANT'
                JOIN agent_tool_calls tc ON tc.run_id = r.id
                JOIN agent_proposals p ON p.run_id = r.id
                WHERE r.user_id = ? AND r.id = ?
                """, owner, run);
        assertThat(joined.get("run_id")).hasToString(run.toString());
        assertThat(joined.get("user_message_id")).hasToString(userMessage.toString());
        assertThat(joined.get("assistant_message_id")).hasToString(assistantMessage.toString());
        assertThat(joined.get("result_ref")).hasToString(resultResource.toString());
        assertThat(joined.get("proposal_id")).hasToString(proposal.toString());
        assertThat(joined.get("proposal_message_id")).hasToString(assistantMessage.toString());

        // The run response now carries the real assistant message id, owner-scoped.
        assertThat(runs.get(owner, run).assistantMessageId()).isEqualTo(assistantMessage);
        assertThatThrownBy(() -> runs.get(stranger, run)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- interruption attribution -----------------------------------------------------

    @Test
    void failedDeadlineAndCancelAttributionNeverFabricatesSuccess() {
        applyV53();
        UUID failedRun = runningRun(owner, conversation);
        var failedContext = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, failedRun);
        UUID interruptedCall = audit.startToolCall(failedContext, "call-slow", "search_web", "{}", "AUTO");
        UUID completedCall = audit.startToolCall(failedContext, "call-done", "list_resumes", "{}", "AUTO");
        audit.finishToolCall(completedCall, owner, "COMPLETED", "{\"resumeId\":\"" + UUID.randomUUID() + "\"}", null);

        AgentRunStore.AgentRun failed = runs.fail(owner, failedRun, "AGENT_UPSTREAM_SILENT", "上游超时", false);
        assertThat(failed.status()).isEqualTo("FAILED");
        var failedCalls = audit.toolCalls(owner, failedRun);
        assertThat(statusOf(failedCalls, "call-slow")).isEqualTo("UNKNOWN");
        assertThat(errorOf(failedCalls, "call-slow")).isEqualTo("AGENT_UPSTREAM_SILENT");
        assertThat(statusOf(failedCalls, "call-done")).isEqualTo("COMPLETED");

        UUID cancelledRun = runningRun(owner, conversation);
        var cancelledContext = new AgentExecutionAuditStore.ToolCallContext(owner, conversation, cancelledRun);
        audit.startToolCall(cancelledContext, "call-active", "update_memory", "{}", "CONFIRMED");
        UUID openProposal = UUID.randomUUID();
        audit.recordProposal(new AgentExecutionAuditStore.ProposalRecord(openProposal, cancelledContext,
                "memory_item", UUID.randomUUID(), null, "memory_item:/api/agent/memory/items/x",
                "记忆", null, "/api/agent/memory/items/x", null));

        AgentRunStore.AgentRun cancelled = runs.cancel(owner, cancelledRun, false);
        assertThat(cancelled.status()).isEqualTo("INTERRUPTED");
        assertThat(cancelled.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(statusOf(audit.toolCalls(owner, cancelledRun), "call-active")).isEqualTo("UNKNOWN");
        assertThat(errorOf(audit.toolCalls(owner, cancelledRun), "call-active")).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(audit.proposals(owner, cancelledRun).getFirst().status()).isEqualTo("CANCELLED");
    }

    // ---- fixtures and helpers ---------------------------------------------------------

    private static final String PRE_V53_SCHEMA = """
            CREATE TABLE users (id UUID PRIMARY KEY);
            CREATE TABLE conversations (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL,
                CONSTRAINT uq_conversations_id_owner UNIQUE (id, user_id),
                CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
            );
            CREATE TABLE messages (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL,
                conversation_id UUID NOT NULL,
                role VARCHAR(32) NOT NULL,
                content TEXT NOT NULL,
                tool_calls JSONB,
                proposal_resolutions JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_messages_id_owner UNIQUE (id, user_id),
                CONSTRAINT fk_messages_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
                CONSTRAINT fk_messages_conversation_owner FOREIGN KEY (conversation_id, user_id)
                    REFERENCES conversations (id, user_id) ON DELETE CASCADE
            );
            CREATE TABLE agent_runs (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL,
                conversation_id UUID NOT NULL,
                request_hash CHAR(64) NOT NULL DEFAULT repeat('0', 64),
                message TEXT NOT NULL DEFAULT 'fixture',
                status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
                label VARCHAR(240) NOT NULL DEFAULT '正在理解你的请求',
                tool_started BOOLEAN NOT NULL DEFAULT FALSE,
                error_code VARCHAR(80),
                error_message TEXT,
                retryable BOOLEAN NOT NULL DEFAULT FALSE,
                assistant_message_id UUID,
                logical_action_id UUID,
                context_references JSONB NOT NULL DEFAULT '[]'::jsonb,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                finished_at TIMESTAMPTZ,
                CONSTRAINT uq_agent_runs_id_owner UNIQUE (id, user_id),
                CONSTRAINT fk_agent_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
                CONSTRAINT fk_agent_runs_conversation_owner FOREIGN KEY (conversation_id, user_id)
                    REFERENCES conversations (id, user_id) ON DELETE CASCADE,
                CONSTRAINT fk_agent_runs_assistant_owner FOREIGN KEY (assistant_message_id, user_id)
                    REFERENCES messages (id, user_id) ON DELETE SET NULL (assistant_message_id)
            );
            CREATE TABLE agent_tool_audit (
                id UUID PRIMARY KEY,
                user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                run_id UUID,
                tool VARCHAR(80) NOT NULL,
                module VARCHAR(40) NOT NULL,
                capability VARCHAR(16) NOT NULL,
                arguments_digest VARCHAR(64) NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """;

    private void applyV53() {
        ClassPathResource migration = new ClassPathResource(V53);
        String sql;
        try (var stream = migration.getInputStream()) {
            sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("V53 migration resource is missing", failure);
        }
        for (String statement : statements(sql)) jdbc.execute(statement);
    }

    /** Splits Flyway SQL without breaking quoted literals or treating {@code --} inside one as a comment. */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean comment = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            if (comment) {
                if (value == '\n') { comment = false; current.append('\n'); }
                continue;
            }
            if (!quoted && value == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                comment = true;
                index++;
                continue;
            }
            if (value == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    current.append("''");
                    index++;
                    continue;
                }
                quoted = !quoted;
                current.append(value);
                continue;
            }
            if (value == ';' && !quoted) {
                if (!current.toString().isBlank()) statements.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(value);
        }
        if (!current.toString().isBlank()) statements.add(current.toString().strip());
        return statements;
    }

    private void insertUser(UUID id) {
        jdbc.update("INSERT INTO users(id) VALUES (?) ON CONFLICT (id) DO NOTHING", id);
    }

    private void insertConversation(UUID id, UUID userId) {
        jdbc.update("INSERT INTO conversations(id, user_id) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", id, userId);
    }

    private void insertMessage(UUID id, UUID userId, UUID conversationId, String role, String content, Instant at) {
        jdbc.update("""
                INSERT INTO messages(id, user_id, conversation_id, role, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, userId, conversationId, role, content,
                java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    private void insertRun(UUID id, UUID userId, UUID conversationId, String status, String message,
            UUID assistantMessageId, String errorCode, Instant createdAt, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO agent_runs(id, user_id, conversation_id, message, status, error_code,
                    assistant_message_id, created_at, updated_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, conversationId, message, status, errorCode, assistantMessageId,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(updatedAt),
                "RUNNING".equals(status) ? null : java.sql.Timestamp.from(updatedAt));
    }

    private UUID runningRun(UUID userId, UUID conversationId) {
        insertConversation(conversationId, userId);
        UUID run = UUID.randomUUID();
        insertRun(run, userId, conversationId, "RUNNING", "fixture", null, null, Instant.now(), Instant.now());
        return run;
    }

    private UUID runOf(UUID messageId) {
        return jdbc.queryForObject("SELECT run_id FROM messages WHERE id = ?", UUID.class, messageId);
    }

    private UUID requestOf(UUID messageId) {
        return jdbc.queryForObject("SELECT request_id FROM messages WHERE id = ?", UUID.class, messageId);
    }

    private static String statusOf(List<AgentExecutionAuditStore.ToolCall> calls, String toolCallId) {
        return calls.stream().filter(call -> toolCallId.equals(call.toolCallId())).findFirst().orElseThrow().status();
    }

    private static String errorOf(List<AgentExecutionAuditStore.ToolCall> calls, String toolCallId) {
        return calls.stream().filter(call -> toolCallId.equals(call.toolCallId())).findFirst().orElseThrow().errorCode();
    }
}

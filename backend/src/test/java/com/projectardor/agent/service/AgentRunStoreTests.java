package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import jakarta.persistence.EntityManager;
import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.domain.ConversationMessage;

class AgentRunStoreTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentConversationStore conversations = mock(AgentConversationStore.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final AgentRunStore runs = new AgentRunStore(jdbc, conversations, entityManager);

    @Test
    void interruptedReplacementReusesLogicalToolActionId() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID originalAction = UUID.randomUUID();
        when(jdbc.queryForList(argThat(sql -> sql.contains("COALESCE(logical_action_id, id)")),
                eq(UUID.class), anyString(), eq(requestId), eq(userId), eq(conversationId)))
                .thenReturn(List.of(originalAction));
        when(jdbc.update(argThat(sql -> sql.contains("logical_action_id")),
                eq(requestId), eq(userId), eq(conversationId), anyString(), eq("学习 JVM"), eq(originalAction)))
                .thenReturn(1);

        UUID action = runs.logicalActionId(userId, requestId, conversationId, "学习 JVM", null, null, List.of());
        assertThat(action).isEqualTo(originalAction);
        assertThat(runs.begin(userId, requestId, conversationId, "学习 JVM", null, null,
                List.of(), action)).isTrue();
    }

    @Test
    void firstRunUsesItsOwnLogicalToolActionId() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        assertThat(runs.logicalActionId(userId, requestId, conversationId, "学习 JVM", null, null, List.of()))
                .isEqualTo(requestId);
    }

    @Test
    void repeatedRequestIdDoesNotInsertAnotherRun() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), eq("你好")))
                .thenReturn(1, 0);
        assertThat(runs.begin(userId, requestId, conversationId, "你好", null, null, List.of())).isTrue();
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), eq(requestId), eq(userId), eq(conversationId), fingerprint.capture(), eq("你好"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq(requestId), eq(userId)))
                .thenReturn(List.of(fingerprint.getValue()));
        assertThat(runs.begin(userId, requestId, conversationId, "你好", null, null, List.of())).isFalse();
    }

    @Test
    void duplicateIdWithDifferentContentsIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), anyString()))
                .thenReturn(1, 0);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(requestId), eq(userId)))
                .thenReturn(List.of("previous-message-hash"));

        assertThat(runs.begin(userId, requestId, conversationId, "第一条", null, null, List.of())).isTrue();
        assertThatThrownBy(() -> runs.begin(userId, requestId, conversationId, "另一条", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求标识已用于另一条消息");
    }

    @Test
    void failureUpdateDisablesRetryAfterAnyToolStarted() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> runs.fail(userId, requestId, "UPSTREAM_ERROR", "网络波动", true))
                .hasMessage("请求记录不存在");
        verify(jdbc).update(argThat(sql -> sql.contains("CASE WHEN tool_started THEN FALSE ELSE ? END")),
                eq("UPSTREAM_ERROR"), eq("网络波动"), eq(true),
                eq(requestId), eq(userId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "RUNNING", "INTERRUPTED" })
    @SuppressWarnings("unchecked")
    void flushesMessagesBeforeLinkingCompletedRun(String status) {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "新对话");
        AgentRunStore.AgentRun running = new AgentRunStore.AgentRun(requestId, conversation.getId(),
                "你好", status, "正在理解", null, null, false, false, Instant.now(), Instant.now());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AgentRunStore.AgentRun>>any(),
                eq(requestId), eq(userId))).thenReturn(List.of(running));
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user(userId, conversation.getId(), "你好", List.of()),
                ConversationMessage.assistant(userId, conversation.getId(), "你好！", null));
        when(conversations.appendExchange(userId, conversation, "你好", "你好！", null, List.of()))
                .thenReturn(messages);
        when(jdbc.update(anyString(), eq(messages.get(1).getId()), eq(requestId), eq(userId))).thenReturn(1);

        assertThat(runs.complete(userId, requestId, conversation, "你好", "你好！", null, List.of()))
                .isEqualTo(messages);
        InOrder order = inOrder(conversations, entityManager, jdbc);
        order.verify(conversations).appendExchange(userId, conversation, "你好", "你好！", null, List.of());
        order.verify(entityManager).flush();
        order.verify(jdbc).update(argThat(sql -> sql.contains("status IN ('RUNNING', 'INTERRUPTED')")),
                eq(messages.get(1).getId()), eq(requestId), eq(userId));
    }

    @Test
    void liveRunHeartbeatPreventsFalseStaleInterruption() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), eq("你好")))
                .thenReturn(1);
        runs.begin(userId, requestId, conversationId, "你好", null, null, List.of());

        runs.heartbeatActiveRuns();

        verify(jdbc).update(argThat(sql -> sql.contains("updated_at = CURRENT_TIMESTAMP")),
                eq(requestId), eq(userId));
    }
}

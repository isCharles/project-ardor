package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.projectardor.assistant.conversation.domain.Conversation;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.service.AgentConversationStore;

class AgentRunStoreTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentConversationStore conversations = mock(AgentConversationStore.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final AgentRunStore runs = new AgentRunStore(jdbc, conversations, entityManager);

    @Test void attachmentSnapshotIsPartOfInitialRunInsertAndOwnerScopedRecovery() {
        UUID owner = UUID.randomUUID(), request = UUID.randomUUID(), conversation = UUID.randomUUID(), resume = UUID.randomUUID();
        var references = List.of(new com.projectardor.assistant.conversation.AgentContextReference("RESUME", resume, "合成测试简历.pdf"));
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(references);
        when(jdbc.update(argThat(sql -> sql.contains("context_references") && sql.contains("INSERT INTO agent_runs")),
                eq(request), eq(owner), eq(conversation), anyString(), eq("请分析附件"), eq(request), eq(json), eq("[]"))).thenReturn(1);
        assertThat(runs.begin(owner, request, conversation, "请分析附件", null, null,
                List.of(new com.projectardor.assistant.conversation.web.AgentContextReferenceRequest("RESUME", resume)), request,
                com.projectardor.assistant.ReasoningDepth.AUTO, references)).isTrue();
        when(jdbc.queryForList(argThat(sql -> sql.contains("context_references::text") && sql.contains("user_id=?") && sql.contains("conversation_id=?")),
                eq(String.class), eq(request), eq(owner), eq(conversation))).thenReturn(List.of(json));
        assertThat(runs.contextReferences(owner, conversation, request)).isEqualTo(references);
        assertThat(runs.contextReferences(UUID.randomUUID(), conversation, request)).isEmpty();
    }

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
                eq(requestId), eq(userId), eq(conversationId), anyString(), eq("学习 JVM"), eq(originalAction), eq("[]"), eq("[]")))
                .thenReturn(1);

        UUID action = runs.logicalActionId(userId, requestId, conversationId, "学习 JVM", null, null, List.of());
        assertThat(action).isEqualTo(originalAction);
        assertThat(runs.begin(userId, requestId, conversationId, "学习 JVM", null, null,
                List.of(), action)).isTrue();
    }

    @Test
    void inheritedActionSurvivesAReplacementFailureBeforeItsFirstTool() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID originalAction = UUID.randomUUID();
        when(jdbc.queryForList(argThat(sql -> sql.contains("logical_action_id <> id")
                        && sql.contains("tool_started OR")),
                eq(UUID.class), anyString(), eq(requestId), eq(userId), eq(conversationId)))
                .thenReturn(List.of(originalAction));

        assertThat(runs.logicalActionId(userId, requestId, conversationId,
                "学习 JVM", null, null, List.of())).isEqualTo(originalAction);
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
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), eq("你好"), eq("[]"), eq("[]")))
                .thenReturn(1, 0);
        assertThat(runs.begin(userId, requestId, conversationId, "你好", null, null, List.of())).isTrue();
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), eq(requestId), eq(userId), eq(conversationId), fingerprint.capture(), eq("你好"), eq("[]"), eq("[]"));
        when(jdbc.queryForList(argThat(sql -> sql.contains("SELECT request_hash")), eq(String.class), eq(requestId), eq(userId)))
                .thenReturn(List.of(fingerprint.getValue()));
        assertThat(runs.begin(userId, requestId, conversationId, "你好", null, null, List.of())).isFalse();
    }

    @Test
    void duplicateIdWithDifferentContentsIsRejected() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), anyString(), eq("[]"), eq("[]")))
                .thenReturn(1, 0);
        when(jdbc.queryForList(argThat(sql -> sql.contains("SELECT request_hash")), eq(String.class), eq(requestId), eq(userId)))
                .thenReturn(List.of("previous-message-hash"));

        assertThat(runs.begin(userId, requestId, conversationId, "第一条", null, null, List.of())).isTrue();
        assertThatThrownBy(() -> runs.begin(userId, requestId, conversationId, "另一条", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求标识已用于另一条消息");
    }

    @Test
    void imageIdsArePartOfTheRequestFingerprint() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID firstImage = UUID.randomUUID();
        UUID secondImage = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(userId), eq(conversationId), anyString(), eq("分析图片"), eq("[]"), anyString()))
                .thenReturn(1);

        runs.begin(userId, UUID.randomUUID(), conversationId, "分析图片", null, null, List.of(),
                null, com.projectardor.assistant.ReasoningDepth.AUTO, List.of(), List.of(firstImage));
        runs.begin(userId, UUID.randomUUID(), conversationId, "分析图片", null, null, List.of(),
                null, com.projectardor.assistant.ReasoningDepth.AUTO, List.of(), List.of(secondImage));

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> persistedImages = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(), eq(userId), eq(conversationId),
                fingerprints.capture(), eq("分析图片"), eq("[]"), persistedImages.capture());
        assertThat(fingerprints.getAllValues().get(0)).isNotEqualTo(fingerprints.getAllValues().get(1));
        assertThat(persistedImages.getAllValues()).containsExactly(
                "[\"" + firstImage + "\"]", "[\"" + secondImage + "\"]");
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
        when(conversations.appendExchange(userId, conversation, "你好", "你好！", null, List.of(), false, List.of()))
                .thenReturn(messages);
        when(jdbc.update(anyString(), eq(messages.get(1).getId()), eq(requestId), eq(userId))).thenReturn(1);

        assertThat(runs.complete(userId, requestId, conversation, "你好", "你好！", null, List.of()))
                .isEqualTo(messages);
        InOrder order = inOrder(conversations, entityManager, jdbc);
        order.verify(conversations).appendExchange(userId, conversation, "你好", "你好！", null, List.of(), false, List.of());
        order.verify(entityManager).flush();
        order.verify(jdbc).update(argThat(sql -> sql.contains("status IN ('RUNNING', 'INTERRUPTED')")),
                eq(messages.get(1).getId()), eq(requestId), eq(userId));
    }

    @Test
    void liveRunHeartbeatPreventsFalseStaleInterruption() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(requestId), eq(userId), eq(conversationId), anyString(), eq("你好"), eq("[]"), eq("[]")))
                .thenReturn(1);
        runs.begin(userId, requestId, conversationId, "你好", null, null, List.of());

        runs.heartbeatActiveRuns();

        verify(jdbc).update(argThat(sql -> sql.contains("updated_at = CURRENT_TIMESTAMP")),
                eq(requestId), eq(userId));
    }
}

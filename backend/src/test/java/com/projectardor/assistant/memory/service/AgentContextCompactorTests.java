package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.execution.service.LangChainModelFactory;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

import dev.langchain4j.model.chat.ChatModel;

class AgentContextCompactorTests {

    @Test
    void summarizesOlderMessagesAndKeepsRecentMessagesVerbatim() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        List<ConversationMessage> messages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            messages.add(ConversationMessage.user(userId, conversationId, "older user " + i));
            messages.add(ConversationMessage.assistant(userId, conversationId, "older assistant " + i));
        }
        AgentConversationStore conversations = mock(AgentConversationStore.class);
        when(conversations.allMessages(userId, conversationId)).thenReturn(messages);
        ConversationSummaryStore summaries = mock(ConversationSummaryStore.class);
        when(summaries.latest(userId, conversationId)).thenReturn(Optional.empty());
        when(summaries.save(any(), any(), anyInt(), any(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> new ConversationSummaryStore.Summary(
                        UUID.randomUUID(), invocation.getArgument(2), invocation.getArgument(3),
                        invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6),
                        invocation.getArgument(7), Instant.now()));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("用户目标：找 Java 后端实习；未完成：补 MySQL。");
        LangChainModelFactory factory = mock(LangChainModelFactory.class);
        when(factory.create(any(LlmConfigService.LlmRuntimeConfig.class), any(ReasoningDepth.class), anyList())).thenReturn(model);

        AgentContextCompactor compactor = new AgentContextCompactor(conversations, summaries, factory, 4, 8, 1000);
        var prepared = compactor.prepare(userId, conversationId, "system", config(), ReasoningDepth.AUTO);

        assertThat(prepared.recentMessages()).hasSize(4);
        assertThat(prepared.recentMessages().getLast().getContent()).isEqualTo("older assistant 11");
        assertThat(prepared.summaryVersion()).isEqualTo(1);
        assertThat(prepared.compactedMessages()).isEqualTo(20);
        assertThat(prepared.systemPrompt()).contains("<conversation_summary", "用户目标：找 Java 后端实习");
        verify(model).chat(anyString());
        verify(summaries).save(any(), any(), anyInt(), any(), anyInt(), anyString(), anyInt(), any());
    }

    @Test
    void doesNotCompactAShortConversation() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user(userId, conversationId, "hi"),
                ConversationMessage.assistant(userId, conversationId, "hello"));
        AgentConversationStore conversations = mock(AgentConversationStore.class);
        when(conversations.allMessages(userId, conversationId)).thenReturn(messages);
        ConversationSummaryStore summaries = mock(ConversationSummaryStore.class);
        when(summaries.latest(userId, conversationId)).thenReturn(Optional.empty());
        LangChainModelFactory factory = mock(LangChainModelFactory.class);

        AgentContextCompactor compactor = new AgentContextCompactor(conversations, summaries, factory, 4, 8, 1000);
        var prepared = compactor.prepare(userId, conversationId, "system", config(), ReasoningDepth.AUTO);

        assertThat(prepared.recentMessages()).hasSize(2);
        assertThat(prepared.summaryVersion()).isZero();
        assertThat(prepared.systemPrompt()).isEqualTo("system");
    }

    private static LlmConfigService.LlmRuntimeConfig config() {
        return new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE,
                "https://example.invalid", "fixture-model", "redacted");
    }
}



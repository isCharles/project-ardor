package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.agent.repository.ConversationRepository;
import com.projectardor.common.web.ResourceNotFoundException;

class AgentConversationStoreTests {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
    private final AgentConversationStore store = new AgentConversationStore(conversationRepository, messageRepository,
            mock(org.springframework.context.ApplicationEventPublisher.class));

    @Test
    void createsIndependentActiveConversations() {
        UUID userId = UUID.randomUUID();
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Conversation first = store.create(userId);
        Conversation second = store.create(userId);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getStatus()).isEqualTo("ACTIVE");
        assertThat(second.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void checksConversationOwnership() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findByIdAndUserId(conversationId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> store.requireActive(userId, conversationId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("会话不存在");
    }

    @Test
    void archivesOnlyOwnedConversation() {
        UUID userId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "测试会话");
        when(conversationRepository.findByIdAndUserId(conversation.getId(), userId))
                .thenReturn(Optional.of(conversation));

        store.archive(userId, conversation.getId());

        assertThat(conversation.getStatus()).isEqualTo("ARCHIVED");
        verify(conversationRepository).save(conversation);
    }

    @Test
    void restoresArchivedConversation() {
        UUID userId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "测试会话");
        conversation.archive();
        when(conversationRepository.findByIdAndUserId(conversation.getId(), userId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(conversation)).thenReturn(conversation);

        Conversation restored = store.restore(userId, conversation.getId());

        assertThat(restored.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void pinsAnOwnedActiveConversation() {
        UUID userId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "重要会话");
        when(conversationRepository.findByIdAndUserId(conversation.getId(), userId))
                .thenReturn(Optional.of(conversation));
        when(conversationRepository.save(conversation)).thenReturn(conversation);

        Conversation pinned = store.setPinned(userId, conversation.getId(), true);

        assertThat(pinned.isPinned()).isTrue();
        verify(conversationRepository).save(conversation);
    }

    @Test
    void createsAConciseTitleFromTheFirstMessage() {
        Conversation conversation = Conversation.create(UUID.randomUUID(), "新对话");

        conversation.titleFromFirstMessage("帮我准备下周的 Java 后端面试，需要重点复习 JVM 和 Redis。后面还有更多说明");

        assertThat(conversation.getTitle()).isEqualTo("帮我准备下周的 Java 后端面试，需要重点复习…");
    }

    @Test void manualNamingIsPreservedEvenIfItMatchesTheDefaultLabel() {
        Conversation conversation = Conversation.create(UUID.randomUUID(), "新对话");
        conversation.rename("新对话");
        conversation.titleFromFirstMessage("不要替换我的标题");
        assertThat(conversation.getTitle()).isEqualTo("新对话");
        assertThat(conversation.isTitleManuallySet()).isTrue();
    }

    @Test
    void asksForAnAutomaticTitleOnlyOnTheFirstExchange() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AgentConversationStore localStore = new AgentConversationStore(
                conversationRepository, messageRepository, events);
        UUID userId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "新对话");
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByUserIdAndConversationId(userId, conversation.getId())).thenReturn(0L, 2L);

        localStore.appendExchange(userId, conversation, "帮我准备 Java 面试", "好的");
        localStore.appendExchange(userId, conversation, "再讲讲 JVM", "好的");

        verify(events).publishEvent(any(ConversationTitleRequested.class));
    }

    @Test
    void keepsTheExistingTitleOnceTheConversationHasHistory() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AgentConversationStore localStore = new AgentConversationStore(
                conversationRepository, messageRepository, events);
        UUID userId = UUID.randomUUID();
        Conversation conversation = Conversation.create(userId, "帮我准备 Java 面试");
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.countByUserIdAndConversationId(userId, conversation.getId())).thenReturn(2L);

        localStore.appendExchange(userId, conversation, "再讲讲 JVM", "好的");

        verify(events, never()).publishEvent(any(ConversationTitleRequested.class));
    }
}

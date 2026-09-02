package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.agent.repository.ConversationRepository;
import com.projectardor.common.web.ResourceNotFoundException;

class AgentConversationStoreTests {

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
    private final AgentConversationStore store = new AgentConversationStore(conversationRepository, messageRepository);

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
}

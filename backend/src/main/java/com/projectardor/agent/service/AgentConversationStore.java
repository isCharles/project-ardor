package com.projectardor.agent.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.agent.domain.Conversation;
import com.projectardor.agent.domain.ConversationMessage;
import com.projectardor.agent.AgentContextReference;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.agent.repository.ConversationRepository;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AgentConversationStore {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    public AgentConversationStore(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(UUID userId) {
        return conversationRepository.findAllByUserIdAndStatusOrderByPinnedDescUpdatedAtDesc(userId, "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<Conversation> listArchived(UUID userId) {
        return conversationRepository.findAllByUserIdAndStatusOrderByPinnedDescUpdatedAtDesc(userId, "ARCHIVED");
    }

    @Transactional(readOnly = true)
    public Conversation requireActive(UUID userId, UUID conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在"));
        if (!"ACTIVE".equals(conversation.getStatus())) {
            throw new ResourceNotFoundException("会话已归档");
        }
        return conversation;
    }

    @Transactional
    public Conversation create(UUID userId) {
        return conversationRepository.save(Conversation.create(userId, "新对话"));
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> recent(UUID userId, UUID conversationId) {
        List<ConversationMessage> messages = new ArrayList<>(
                messageRepository.findTop40ByUserIdAndConversationIdOrderByCreatedAtDesc(
                        userId, conversationId));
        Collections.reverse(messages);
        return messages;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> allMessages(UUID userId, UUID conversationId) {
        return messageRepository.findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
    }

    @Transactional
    public List<ConversationMessage> appendExchange(
            UUID userId, Conversation conversation, String userText, String assistantText) {
        return appendExchange(userId, conversation, userText, assistantText, null);
    }

    @Transactional
    public List<ConversationMessage> appendExchange(
            UUID userId, Conversation conversation, String userText, String assistantText, String runTrace) {
        return appendExchange(userId, conversation, userText, assistantText, runTrace, List.of());
    }

    @Transactional
    public List<ConversationMessage> appendExchange(
            UUID userId, Conversation conversation, String userText, String assistantText, String runTrace,
            List<AgentContextReference> contextReferences) {
        List<ConversationMessage> saved = messageRepository.saveAll(List.of(
                ConversationMessage.user(userId, conversation.getId(), userText, contextReferences),
                ConversationMessage.assistant(userId, conversation.getId(), assistantText, runTrace)));
        conversation.titleFromFirstMessage(userText);
        conversation.touch();
        conversationRepository.save(conversation);
        return saved;
    }

    @Transactional
    public Conversation rename(UUID userId, UUID conversationId, String rawTitle) {
        Conversation conversation = requireActive(userId, conversationId);
        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.isBlank()) throw new IllegalArgumentException("会话标题不能为空");
        if (title.length() > 240) throw new IllegalArgumentException("会话标题不能超过 240 个字符");
        conversation.rename(title);
        return conversationRepository.save(conversation);
    }

    @Transactional
    public Conversation setPinned(UUID userId, UUID conversationId, boolean pinned) {
        Conversation conversation = requireActive(userId, conversationId);
        conversation.setPinned(pinned);
        return conversationRepository.save(conversation);
    }

    @Transactional
    public void archive(UUID userId, UUID conversationId) {
        Conversation conversation = requireActive(userId, conversationId);
        conversation.archive();
        conversationRepository.save(conversation);
    }

    @Transactional
    public Conversation restore(UUID userId, UUID conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在"));
        if (!"ARCHIVED".equals(conversation.getStatus())) {
            throw new IllegalStateException("只有已归档会话可以恢复");
        }
        conversation.restore();
        return conversationRepository.save(conversation);
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        Conversation conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在"));
        conversationRepository.delete(conversation);
    }
}

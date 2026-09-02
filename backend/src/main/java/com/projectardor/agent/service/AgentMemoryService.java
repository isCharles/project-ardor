package com.projectardor.agent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.agent.domain.AgentMemoryItem;
import com.projectardor.agent.repository.AgentMemoryRepository;
import com.projectardor.agent.repository.AgentMemoryItemRepository;
import com.projectardor.agent.web.AgentMemoryResponse;
import com.projectardor.agent.web.AgentMemoryItemResponse;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AgentMemoryService {

    public static final int MAX_LENGTH = 4000;

    private final AgentMemoryRepository repository;
    private final AgentMemoryItemRepository itemRepository;

    public AgentMemoryService(AgentMemoryRepository repository, AgentMemoryItemRepository itemRepository) {
        this.repository = repository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public AgentMemoryResponse get(UUID userId) {
        var items = itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        Instant updatedAt = items.stream().map(AgentMemoryItem::getUpdatedAt).max(Instant::compareTo).orElse(null);
        return new AgentMemoryResponse(items.stream().map(AgentMemoryItem::getContent)
                .collect(java.util.stream.Collectors.joining("\n")), updatedAt);
    }

    @Transactional(readOnly = true)
    public String content(UUID userId) {
        return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(item -> "- " + item.getContent()).collect(java.util.stream.Collectors.joining("\n"));
    }

    @Transactional
    public AgentMemoryResponse update(UUID userId, String rawContent) {
        String content = normalize(rawContent);
        itemRepository.deleteAllByUserId(userId);
        java.util.Arrays.stream(content.split("\\R")).map(String::strip)
                .map(line -> line.replaceFirst("^[-*•]\\s*", ""))
                .filter(line -> !line.isBlank()).distinct().limit(50)
                .forEach(line -> itemRepository.save(AgentMemoryItem.create(userId, itemContent(line))));
        repository.findByUserId(userId).ifPresent(repository::delete);
        return get(userId);
    }

    @Transactional(readOnly = true)
    public java.util.List<AgentMemoryItemResponse> listItems(UUID userId) {
        return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream().map(AgentMemoryItemResponse::from).toList();
    }

    @Transactional
    public AgentMemoryItemResponse add(UUID userId, String rawContent) {
        String content = itemContent(rawContent);
        if (itemRepository.existsByUserIdAndContentIgnoreCase(userId, content)) {
            return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                    .filter(item -> item.getContent().equalsIgnoreCase(content)).findFirst()
                    .map(AgentMemoryItemResponse::from).orElseThrow();
        }
        return AgentMemoryItemResponse.from(itemRepository.save(AgentMemoryItem.create(userId, content)));
    }

    @Transactional
    public void remove(UUID userId, UUID memoryId) {
        itemRepository.delete(itemRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在")));
    }

    @Transactional
    public void clear(UUID userId) {
        itemRepository.deleteAllByUserId(userId);
        repository.findByUserId(userId).ifPresent(repository::delete);
    }

    private String itemContent(String content) {
        String normalized = content == null ? "" : content.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        if (normalized.length() > 1000) throw new IllegalArgumentException("单条记忆不能超过 1000 个字符");
        return normalized;
    }

    private String normalize(String content) {
        String normalized = content == null ? "" : content.strip();
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("总体记忆不能超过 " + MAX_LENGTH + " 个字符");
        }
        return normalized;
    }
}

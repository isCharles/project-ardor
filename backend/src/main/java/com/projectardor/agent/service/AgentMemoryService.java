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
        var items = validItems(userId);
        Instant updatedAt = items.stream()
                .map(AgentMemoryItem::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new AgentMemoryResponse(items.stream().map(AgentMemoryItem::getContent)
                .collect(java.util.stream.Collectors.joining("\n")), updatedAt);
    }

    @Transactional(readOnly = true)
    public String content(UUID userId) {
        return validItems(userId).stream()
                .map(item -> "- " + item.getContent()).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** Only facts that are still true are injected or shown as current memory. */
    public java.util.List<AgentMemoryItem> validItems(UUID userId) {
        return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                .filter(AgentMemoryItem::isValid).toList();
    }

    @Transactional
    public AgentMemoryResponse update(UUID userId, String rawContent) {
        itemRepository.lockOwner(userId);
        String content = normalize(rawContent);
        java.util.List<String> desired = java.util.Arrays.stream(content.split("\\R")).map(String::strip)
                .map(line -> line.replaceFirst("^[-*•]\\s*", ""))
                .filter(line -> !line.isBlank())
                .map(this::itemContent)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                line -> line.toLowerCase(java.util.Locale.ROOT),
                                line -> line,
                                (left, right) -> left,
                                java.util.LinkedHashMap::new),
                        values -> values.values().stream().limit(50).toList()));
        var existing = validItems(userId);
        java.util.Set<String> desiredKeys = desired.stream()
                .map(line -> line.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        existing.stream()
                .filter(item -> !desiredKeys.contains(item.getContent().toLowerCase(java.util.Locale.ROOT)))
                .forEach(itemRepository::delete);
        java.util.Set<String> existingKeys = existing.stream()
                .map(item -> item.getContent().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        desired.stream()
                .filter(line -> !existingKeys.contains(line.toLowerCase(java.util.Locale.ROOT)))
                .forEach(line -> itemRepository.save(AgentMemoryItem.create(userId, line)));
        repository.findByUserId(userId).ifPresent(repository::delete);
        return get(userId);
    }

    @Transactional(readOnly = true)
    public java.util.List<AgentMemoryItemResponse> listItems(UUID userId) {
        return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream().map(AgentMemoryItemResponse::from).toList();
    }

    @Transactional
    public AgentMemoryItemResponse add(UUID userId, String rawContent) {
        return add(userId, rawContent, "fact", "USER_EXPLICIT", null);
    }

    @Transactional
    public AgentMemoryItemResponse add(UUID userId, String rawContent, String kind, String sourceKind, UUID sourceRunId) {
        return add(userId, rawContent, kind, sourceKind, sourceRunId, "INFERRED".equals(sourceKind) ? 0.7f : 1.0f);
    }

    @Transactional
    public AgentMemoryItemResponse add(UUID userId, String rawContent, String kind, String sourceKind, UUID sourceRunId, float confidence) {
        itemRepository.lockOwner(userId);
        String content = itemContent(rawContent);
        if (itemRepository.existsByUserIdAndContentIgnoreCase(userId, content)) {
            return itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                    .filter(item -> item.isValid() && item.getContent().equalsIgnoreCase(content)).findFirst()
                    .map(AgentMemoryItemResponse::from)
                    .orElseGet(() -> AgentMemoryItemResponse.from(
                            itemRepository.save(AgentMemoryItem.create(userId, content, kind, sourceKind, sourceRunId, confidence))));
        }
        return AgentMemoryItemResponse.from(
                itemRepository.save(AgentMemoryItem.create(userId, content, kind, sourceKind, sourceRunId, confidence)));
    }

    @Transactional
    public AgentMemoryItemResponse replace(UUID userId, UUID memoryId, String rawContent, String kind, String sourceKind) {
        return replace(userId, memoryId, rawContent, kind, sourceKind, "INFERRED".equals(sourceKind) ? 0.7f : 1.0f);
    }

    @Transactional
    public AgentMemoryItemResponse replace(UUID userId, UUID memoryId, String rawContent, String kind, String sourceKind, float confidence) {
        itemRepository.lockOwner(userId);
        AgentMemoryItem existing = itemRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
        if (!existing.isValid()) throw new IllegalArgumentException("记忆已被替换，请读取当前记忆后再更新");
        String content = itemContent(rawContent);
        if (existing.getContent().equalsIgnoreCase(content)) return AgentMemoryItemResponse.from(existing);
        AgentMemoryItem replacement = itemRepository.save(AgentMemoryItem.create(userId, content,
                kind == null ? existing.getKind() : kind, sourceKind, null, confidence));
        // The old fact stays as history with a closed validity interval.
        existing.invalidate(Instant.now(), replacement.getId());
        itemRepository.saveAndFlush(existing);
        return AgentMemoryItemResponse.from(replacement);
    }

    /** Marks one fact as superseded by another without deleting history. */
    @Transactional
    public void supersede(UUID userId, UUID memoryId, UUID supersededBy) {
        itemRepository.lockOwner(userId);
        AgentMemoryItem item = itemRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
        if (!item.isValid()) return;
        item.invalidate(Instant.now(), supersededBy);
        itemRepository.saveAndFlush(item);
    }

    @Transactional
    public void remove(UUID userId, UUID memoryId) {
        itemRepository.lockOwner(userId);
        itemRepository.delete(itemRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在")));
    }

    @Transactional
    public void removeSnapshot(UUID userId, java.util.List<UUID> ids) {
        itemRepository.lockOwner(userId);
        var items = ids.stream().distinct().map(id -> itemRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("确认列表已变化，请重新生成删除建议"))).toList();
        itemRepository.deleteAll(items);
    }

    @Transactional
    public AgentMemoryItemResponse edit(UUID userId, UUID memoryId, String rawContent) {
        itemRepository.lockOwner(userId);
        AgentMemoryItem item = itemRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆不存在"));
        String content = itemContent(rawContent);
        if (!item.getContent().equalsIgnoreCase(content) && itemRepository.existsByUserIdAndContentIgnoreCase(userId, content)) {
            throw new IllegalArgumentException("已有相同记忆，请修改原条目，不会自动合并或删除");
        }
        boolean changed = !item.getContent().equals(content);
        item.revise(content);
        var saved = itemRepository.saveAndFlush(item);
        if (changed) itemRepository.invalidateEmbedding(userId, memoryId);
        return AgentMemoryItemResponse.from(saved);
    }

    @Transactional
    public void clear(UUID userId) {
        itemRepository.lockOwner(userId);
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

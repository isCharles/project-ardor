package com.projectardor.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.agent.domain.AgentMemoryItem;
import com.projectardor.agent.repository.MemoryVectorStore;

/**
 * Daily memory hygiene: close low-confidence inferred facts that aged out, and fold a
 * shorter duplicate into the longer fact that contains it. Nothing is deleted.
 */
@Component
public class MemoryMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceService.class);
    static final double EXPIRE_CONFIDENCE = 0.5;
    static final int EXPIRE_AFTER_DAYS = 60;
    static final int MIN_MERGE_LENGTH = 6;

    private final MemoryVectorStore vectorStore;
    private final AgentMemoryService memoryService;

    public MemoryMaintenanceService(MemoryVectorStore vectorStore, AgentMemoryService memoryService) {
        this.vectorStore = vectorStore;
        this.memoryService = memoryService;
    }

    @Scheduled(initialDelayString = "${app.memory.maintenance-initial-delay:5m}",
            fixedDelayString = "${app.memory.maintenance-interval:24h}")
    public void maintain() {
        int expired = 0;
        int merged = 0;
        for (UUID userId : vectorStore.usersWithMemories(50)) {
            try {
                expired += vectorStore.expireLowConfidence(userId, EXPIRE_CONFIDENCE, EXPIRE_AFTER_DAYS);
                for (MergePair pair : mergeCandidates(memoryService.validItems(userId))) {
                    memoryService.supersede(userId, pair.dropId(), pair.keepId());
                    merged++;
                }
            } catch (RuntimeException exception) {
                log.warn("Memory maintenance deferred: userId={}, cause={}",
                        userId, exception.getClass().getSimpleName());
            }
        }
        if (expired > 0 || merged > 0) {
            log.info("Memory maintenance: expired={}, merged={}", expired, merged);
        }
    }

    static boolean shouldExpire(AgentMemoryItem item, Instant now) {
        if (!item.isValid()) return false;
        return shouldExpire(item.getSourceKind(), item.getConfidence(), item.getValidFrom(), item.getCreatedAt(), now);
    }

    static boolean shouldExpire(String sourceKind, double confidence, Instant validFrom, Instant createdAt, Instant now) {
        if (!"INFERRED".equals(sourceKind) || confidence >= EXPIRE_CONFIDENCE) return false;
        Instant since = validFrom != null ? validFrom : createdAt;
        return since != null && Duration.between(since, now).toDays() >= EXPIRE_AFTER_DAYS;
    }

    /** A shorter fact fully contained in a longer one is a duplicate; keep the longer. */
    static List<MergePair> mergeCandidates(List<AgentMemoryItem> items) {
        List<AgentMemoryItem> sorted = new ArrayList<>(items.stream().filter(AgentMemoryItem::isValid).toList());
        sorted.sort(Comparator.comparingInt((AgentMemoryItem item) -> normalized(item.getContent()).length()).reversed());
        List<MergePair> pairs = new ArrayList<>();
        Set<UUID> dropped = new HashSet<>();
        for (int keepIndex = 0; keepIndex < sorted.size(); keepIndex++) {
            AgentMemoryItem keep = sorted.get(keepIndex);
            if (dropped.contains(keep.getId())) continue;
            String keepText = normalized(keep.getContent());
            for (int dropIndex = keepIndex + 1; dropIndex < sorted.size(); dropIndex++) {
                AgentMemoryItem drop = sorted.get(dropIndex);
                if (dropped.contains(drop.getId())) continue;
                String dropText = normalized(drop.getContent());
                if (dropText.length() < MIN_MERGE_LENGTH) continue;
                if (keepText.contains(dropText)) {
                    pairs.add(new MergePair(keep.getId(), drop.getId()));
                    dropped.add(drop.getId());
                }
            }
        }
        return pairs;
    }

    private static String normalized(String content) {
        return content == null ? "" : content.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
    }

    record MergePair(UUID keepId, UUID dropId) {}
}

package com.projectardor.materials.knowledge.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

@Service
public class KnowledgeIndexDispatcher {
    private final KnowledgeIndexer indexer;
    public KnowledgeIndexDispatcher(KnowledgeIndexer indexer) { this.indexer=indexer; }
    @Async("knowledgeIndexExecutor")
    public void dispatch(UUID userId,List<UUID> ids,List<String> contents) {
        indexer.indexNewChunks(userId,ids,contents);
    }
    @Async("knowledgeIndexExecutor")
    public void dispatchPending(UUID userId) { indexer.reconcileModel(userId); indexer.backfill(userId,64); }
}

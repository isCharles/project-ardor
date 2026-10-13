package com.projectardor.knowledge.service;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.web.ResourceNotFoundException;

/** Durable indexing progress only; existing chunks remain the work queue. */
@Service
public class KnowledgeIndexTaskStore {
    private final JdbcTemplate jdbc;
    private final java.util.concurrent.ConcurrentHashMap<UUID,Run> active = new java.util.concurrent.ConcurrentHashMap<>();
    public KnowledgeIndexTaskStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<Run> begin(UUID userId, List<UUID> chunks) {
        if (chunks.isEmpty()) return List.of();
        String ids = "{" + String.join(",", chunks.stream().map(UUID::toString).toList()) + "}";
        var documents = jdbc.query("SELECT document_id,array_agg(id) AS ids FROM knowledge_chunks WHERE user_id=? AND embedding IS NULL AND id=ANY(CAST(? AS uuid[])) GROUP BY document_id ORDER BY document_id",
                (row,index) -> {
                    var array = row.getArray("ids");
                    try { return new DocumentChunks(row.getObject(1,UUID.class),List.of((UUID[])array.getArray())); }
                    finally { array.free(); }
                }, userId, ids);
        List<Run> runs = new ArrayList<>();
        for (var entry : documents) {
            UUID document = entry.documentId();
            jdbc.update("""
                INSERT INTO knowledge_index_tasks(id,user_id,document_id) VALUES(?,?,?)
                ON CONFLICT(document_id) DO NOTHING
                """, UUID.randomUUID(),userId,document);
            UUID lease = UUID.randomUUID();
            runs.addAll(jdbc.query("""
                UPDATE knowledge_index_tasks SET status='RUNNING', attempts=attempts+1, lease_id=?,
                  generation=generation+CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,
                  started_at=CURRENT_TIMESTAMP, finished_at=NULL, updated_at=CURRENT_TIMESTAMP, error_code=NULL
                WHERE user_id=? AND document_id=?
                  AND (status<>'RUNNING' OR updated_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes')
                RETURNING id
                """, (row,index) -> new Run(row.getObject(1,UUID.class),userId,document,lease,entry.chunks()), lease,userId,document));
        }
        runs.forEach(run -> active.put(run.lease(),run));
        return runs;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(List<Run> runs, String failureCode) {
        for (Run run : runs) {
            long pending = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE user_id=? AND document_id=? AND embedding IS NULL", Long.class,run.userId(),run.documentId());
            String status = failureCode != null ? "FAILED" : pending == 0 ? "COMPLETED" : "QUEUED";
            jdbc.update("""
                UPDATE knowledge_index_tasks SET status=?, error_code=?, lease_id=NULL, updated_at=CURRENT_TIMESTAMP,
                  finished_at=CASE WHEN ? IN ('COMPLETED','FAILED') THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id=? AND user_id=? AND lease_id=? AND status='RUNNING'
                """,status,failureCode,status,run.id(),run.userId(),run.lease());
            active.remove(run.lease());
        }
    }
    @Transactional
    public void retry(UUID userId, UUID id) {
        var status = jdbc.query("SELECT status FROM knowledge_index_tasks WHERE id=? AND user_id=?",
                (row,index) -> row.getString(1),id,userId).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("向量化任务不存在"));
        if (!"FAILED".equals(status)) throw new IllegalStateException("只有失败任务可以重试");
        jdbc.update("UPDATE knowledge_index_tasks SET status='QUEUED', generation=generation+1, error_code=NULL, finished_at=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND status='FAILED'",id,userId);
    }
    private record DocumentChunks(UUID documentId,List<UUID> chunks) {}
    public void queueDocument(UUID userId, UUID documentId) {
        jdbc.update("INSERT INTO knowledge_index_tasks(id,user_id,document_id) VALUES(?,?,?) ON CONFLICT(document_id) DO NOTHING",UUID.randomUUID(),userId,documentId);
    }
    public record Run(UUID id, UUID userId, UUID documentId, UUID lease,List<UUID> chunks) {}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public void maintain() {
        active.values().forEach(run -> jdbc.update("UPDATE knowledge_index_tasks SET updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND lease_id=? AND status='RUNNING'",run.id(),run.userId(),run.lease()));
        jdbc.update("""
                UPDATE knowledge_index_tasks t SET
                  status=CASE WHEN EXISTS(SELECT 1 FROM knowledge_chunks c WHERE c.user_id=t.user_id AND c.document_id=t.document_id AND c.embedding IS NULL) THEN 'FAILED' ELSE 'COMPLETED' END,
                  error_code=CASE WHEN EXISTS(SELECT 1 FROM knowledge_chunks c WHERE c.user_id=t.user_id AND c.document_id=t.document_id AND c.embedding IS NULL) THEN 'EMBEDDING_INTERRUPTED' ELSE NULL END,
                  finished_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP, lease_id=NULL
                WHERE status='RUNNING' AND updated_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes'
                """);
    }
}

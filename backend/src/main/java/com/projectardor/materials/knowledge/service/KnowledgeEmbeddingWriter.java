package com.projectardor.materials.knowledge.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import com.projectardor.materials.knowledge.repository.KnowledgeVectorStore;
import com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector;

@Service
public class KnowledgeEmbeddingWriter {
    private final KnowledgeVectorStore vectors;
    public KnowledgeEmbeddingWriter(KnowledgeVectorStore vectors) { this.vectors=vectors; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void write(UUID userId,List<UUID> chunks,List<EmbeddingVector> embeddings) {
        if(chunks.size()!=embeddings.size()) throw new IllegalStateException("向量数量与片段不匹配");
        for(int index=0;index<chunks.size();index++) {
            var vector=embeddings.get(index);
            vectors.saveEmbedding(userId,chunks.get(index),vector.toPgVectorLiteral(),vector.dimension(),vector.model());
        }
    }
}

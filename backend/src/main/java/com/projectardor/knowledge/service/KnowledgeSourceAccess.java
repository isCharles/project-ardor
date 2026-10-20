package com.projectardor.knowledge.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.knowledge.repository.KnowledgeDocumentRepository;

/** Knowledge owns reference validation; consumers do not query its Repository. */
@Service
public class KnowledgeSourceAccess {
    private final KnowledgeDocumentRepository documents;
    public KnowledgeSourceAccess(KnowledgeDocumentRepository documents) { this.documents = documents; }
    public void require(UUID owner, UUID id) {
        if (id == null || documents.findByIdAndUserId(id, owner).isEmpty()) throw new ResourceNotFoundException("知识库来源不存在");
    }
}

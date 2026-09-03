package com.projectardor.knowledge.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.knowledge.service.KnowledgeService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocumentResponse upload(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return knowledgeService.upload(principal.userId(), file);
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return knowledgeService.list(principal.userId());
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID documentId) {
        knowledgeService.delete(principal.userId(), documentId);
    }

    @GetMapping("/search")
    public List<KnowledgeSearchResult> search(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit) {
        return knowledgeService.search(principal.userId(), query, limit);
    }

    @PostMapping("/research")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeResearchResponse research(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody KnowledgeResearchRequest request) {
        return knowledgeService.researchFromWeb(principal.userId(), request.query());
    }
}

package com.projectardor.materials.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.materials.knowledge.domain.KnowledgeDocument;
import com.projectardor.materials.knowledge.domain.KnowledgeSourceType;
import com.projectardor.materials.knowledge.web.*;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeApplicationContractTests {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void documentSnapshotAndLegacyHttpKeepExactlyTheSameFieldsAndNulls() {
        UUID id = UUID.randomUUID();
        var time = Instant.parse("2026-10-22T00:00:00Z");
        var view = new KnowledgeDocumentView(id, "JVM", KnowledgeSourceType.USER_UPLOAD,
                null, "jvm.md", 2, time);
        JsonNode actual = mapper.valueToTree(KnowledgeDocumentResponse.fromView(view));
        JsonNode expected = mapper.valueToTree(new KnowledgeDocumentResponse(
                id, "JVM", KnowledgeSourceType.USER_UPLOAD, null, "jvm.md", 2, time));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.size()).isEqualTo(7);
        assertThat(actual.get("sourceUrl").isNull()).isTrue();
        assertThat(mapper.<JsonNode>valueToTree(view)).isEqualTo(actual);
    }

    @Test
    void researchSnapshotCopiesItsListAndHttpKeepsTheSameEnvelope() {
        var document = KnowledgeDocument.fromWeb(UUID.randomUUID(), "GC", "https://example.com", "synthetic");
        var documents = new ArrayList<>(List.of(KnowledgeDocumentView.from(document, 1)));
        var view = new KnowledgeResearchView("GC", documents);
        documents.clear();
        assertThat(view.imported()).hasSize(1);
        assertThatThrownBy(() -> view.imported().clear()).isInstanceOf(UnsupportedOperationException.class);
        JsonNode actual = mapper.valueToTree(KnowledgeResearchResponse.fromView(view));
        assertThat(actual.size()).isEqualTo(2);
        assertThat(actual.get("imported").size()).isEqualTo(1);
        assertThat(mapper.<JsonNode>valueToTree(view)).isEqualTo(actual);
    }

    @Test
    void searchHitRetainsContentScoreAndOptionalSourceWithoutNewFields() {
        var hit = new KnowledgeSearchHit(UUID.randomUUID(), "GC", KnowledgeSourceType.USER_UPLOAD,
                null, "Untrusted source content", 0.03252);
        JsonNode actual = mapper.valueToTree(KnowledgeSearchResult.fromView(hit));
        assertThat(actual.size()).isEqualTo(6);
        assertThat(actual.get("score").asDouble()).isEqualTo(0.03252);
        assertThat(actual.get("sourceUrl").isNull()).isTrue();
        assertThat(mapper.<JsonNode>valueToTree(hit)).isEqualTo(actual);
    }

    @Test
    void httpUsesAuthenticatedOwnerAndMapsAllApplicationResults() {
        UUID owner = UUID.randomUUID();
        var principal = mock(ArdorPrincipal.class);
        when(principal.userId()).thenReturn(owner);
        var service = mock(KnowledgeService.class);
        var controller = new KnowledgeController(service);
        var document = KnowledgeDocumentView.from(
                KnowledgeDocument.uploaded(owner, "GC", "gc.md", "text/markdown", "synthetic"), 1);
        var file = new MockMultipartFile("file", new byte[] {1});
        var hit = new KnowledgeSearchHit(document.id(), document.title(), document.sourceType(),
                null, "synthetic", 0.01639);
        when(service.upload(owner, file)).thenReturn(document);
        when(service.list(owner)).thenReturn(List.of(document));
        when(service.search(owner, "GC", 3)).thenReturn(List.of(hit));
        when(service.researchFromWeb(owner, "GC")).thenReturn(new KnowledgeResearchView("GC", List.of(document)));
        assertThat(controller.upload(principal, file)).isEqualTo(KnowledgeDocumentResponse.fromView(document));
        assertThat(controller.list(principal)).containsExactly(KnowledgeDocumentResponse.fromView(document));
        assertThat(controller.search(principal, "GC", 3)).containsExactly(KnowledgeSearchResult.fromView(hit));
        assertThat(controller.research(principal, new KnowledgeResearchRequest("GC")).imported())
                .containsExactly(KnowledgeDocumentResponse.fromView(document));
        verify(service).upload(owner, file);
        verify(service).list(owner);
        verify(service).search(owner, "GC", 3);
        verify(service).researchFromWeb(owner, "GC");
    }

    @Test
    void providerOperationsHaveNoNewOuterTransaction() throws Exception {
        assertThat(KnowledgeService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(KnowledgeService.class.getMethod("researchFromWeb", UUID.class, String.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(KnowledgeService.class.getMethod("search", UUID.class, String.class, int.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}

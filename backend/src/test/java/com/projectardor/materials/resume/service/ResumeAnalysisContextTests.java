package com.projectardor.materials.resume.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.materials.resume.domain.ResumeAnalysis;
import com.projectardor.materials.resume.repository.ResumeAnalysisRepository;
import com.projectardor.materials.resume.repository.ResumeRepository;
import com.projectardor.materials.resume.storage.ResumeStorage;
import com.projectardor.materials.resume.web.ResumeAnalysisResponse;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResumeAnalysisContextTests {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final ResumeService service = new ResumeService(resumes, analyses, mock(ResumeStorage.class),
            mock(ResumeTextExtractor.class), llm, new LlmJsonParser(mapper), mapper, mock(ResumeScoringStore.class));

    @Test
    void analysisContextUsesBothOwnerAndAnalysisIdentityWithoutModelOrFileAccess() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        var entity = ResumeAnalysis.create(owner, UUID.randomUUID(), Map.of("overallScore", 82), "fixture");
        when(analyses.findByIdAndUserId(entity.getId(), owner)).thenReturn(Optional.of(entity));
        when(analyses.findByIdAndUserId(entity.getId(), other)).thenReturn(Optional.empty());
        var context = service.analysisContext(owner, entity.getId());
        assertThat(context.id()).isEqualTo(entity.getId());
        assertThat(context.resumeId()).isEqualTo(entity.getResumeId());
        assertThat(context.analysis()).containsEntry("overallScore", 82);
        assertThatThrownBy(() -> service.analysisContext(other, entity.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(resumes, llm);
        verify(analyses, never()).save(any());
        verify(analyses).findByIdAndUserId(entity.getId(), owner);
        verify(analyses).findByIdAndUserId(entity.getId(), other);
    }

    @Test
    void nestedAnalysisIsDetachedImmutableAndKeepsNullValues() {
        var items = new ArrayList<Object>();
        items.add("synthetic");
        items.add(null);
        var nested = new LinkedHashMap<String, Object>();
        nested.put("items", items);
        nested.put("optional", null);
        var entity = ResumeAnalysis.create(UUID.randomUUID(), UUID.randomUUID(), Map.of("nested", nested), null);
        var context = ResumeAnalysisSnapshot.from(entity);
        JsonNode before = mapper.valueToTree(context);
        items.clear();
        nested.clear();
        assertThat(mapper.<JsonNode>valueToTree(context)).isEqualTo(before);
        assertThat(before.path("analysis").path("nested").path("items").get(1).isNull()).isTrue();
        assertThatThrownBy(() -> context.analysis().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) context.analysis().get("nested")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) ((Map<?, ?>) context.analysis().get("nested")).get("items")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyResponseRetainsExactlyItsSixFieldsAndMissingTimestamp() throws Exception {
        var entity = ResumeAnalysis.create(UUID.randomUUID(), UUID.randomUUID(), Map.of("overallScore", 82), "fixture");
        var context = ResumeAnalysisSnapshot.from(entity);
        JsonNode old = mapper.valueToTree(new ResumeAnalysisResponse(entity.getId(), entity.getResumeId(),
                entity.getAnalysis(), entity.getModelName(), entity.getPromptVersion(), entity.getCreatedAt()));
        assertThat(old.size()).isEqualTo(6);
        assertThat(old.get("createdAt").isNull()).isTrue();
        assertThat(mapper.<JsonNode>valueToTree(ResumeAnalysisResponse.fromSnapshot(context))).isEqualTo(old);
        assertThat(service.getClass().getMethod("analysisContext", UUID.class, UUID.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }
}

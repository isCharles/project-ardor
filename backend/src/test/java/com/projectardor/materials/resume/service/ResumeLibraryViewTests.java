package com.projectardor.materials.resume.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.materials.resume.domain.*;
import com.projectardor.materials.resume.repository.*;
import com.projectardor.materials.resume.storage.ResumeStorage;
import com.projectardor.materials.resume.web.*;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.model.llm.service.LlmGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResumeLibraryViewTests {
    private final UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
    private final ResumeStorage storage = mock(ResumeStorage.class);
    private final ResumeTextExtractor extractor = mock(ResumeTextExtractor.class);
    private final LlmGateway model = mock(LlmGateway.class);
    private final ResumeService service = new ResumeService(resumes, analyses, storage, extractor, model,
            new LlmJsonParser(json), json, mock(ResumeScoringStore.class));

    private Resume resume(String name) {
        return Resume.create(UUID.randomUUID(), owner, name, "application/pdf", "PRIVATE_STORAGE_KEY", 100, "checksum");
    }

    @Test void listingSelectsTheOriginalFirstAnalysisAndKeepsOrderAndScoreRules() {
        var first = resume("first.pdf"); var second = resume("second.pdf"); var third = resume("third.pdf");
        var latest = ResumeAnalysis.create(owner, first.getId(), Map.of("overallScore", 101), "new");
        var old = ResumeAnalysis.create(owner, first.getId(), Map.of("overallScore", 20), "old");
        var other = ResumeAnalysis.create(owner, second.getId(), Map.of("overallScore", "82"), "text-score");
        when(analyses.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(latest, other, old));
        when(resumes.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(first, second, third));
        var views = service.listViews(owner);
        assertThat(views).extracting(ResumeView::id).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(views.getFirst().analysisId()).isEqualTo(latest.getId());
        assertThat(views.getFirst().overallScore()).isEqualTo(100);
        assertThat(views.get(1).overallScore()).isNull();
        assertThat(views.get(2).analysisId()).isNull();
        assertThatThrownBy(() -> views.clear()).isInstanceOf(UnsupportedOperationException.class);
        first.markParsed("PRIVATE_SOURCE_TEXT");
        assertThat(views.getFirst().parseStatus()).isEqualTo(ResumeParseStatus.PENDING);
        assertThat(json.writeValueAsString(views)).doesNotContain("PRIVATE_STORAGE_KEY", "PRIVATE_SOURCE_TEXT", owner.toString());
        assertThat(service.listViews(foreign)).isEmpty();
        verifyNoInteractions(storage, extractor, model);
        verify(analyses, never()).save(any());
    }

    @Test void detailRequiresTheRealOwnerWithoutReadingFilesOrAnalyses() {
        var resume = resume("owned.pdf");
        when(resumes.findByIdAndUserId(resume.getId(), owner)).thenReturn(Optional.of(resume));
        var view = service.detail(owner, resume.getId());
        assertThat(view.originalFilename()).isEqualTo("owned.pdf");
        assertThat(view.analysisId()).isNull();
        assertThatThrownBy(() -> service.detail(foreign, resume.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(resumes).findByIdAndUserId(resume.getId(), foreign);
        verifyNoInteractions(analyses, storage, extractor, model);
    }

    @Test void reportIdentityAndParentBindingAreEnforcedInTheApplicationLayer() {
        var resume = resume("owned.pdf");
        var analysis = ResumeAnalysis.create(owner, resume.getId(), Map.of("overallScore", 82), "fixture");
        when(analyses.findByIdAndUserId(analysis.getId(), owner)).thenReturn(Optional.of(analysis));
        assertThat(service.report(owner, resume.getId(), analysis.getId()).id()).isEqualTo(analysis.getId());
        assertThatThrownBy(() -> service.report(owner, UUID.randomUUID(), analysis.getId()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("简历分析不存在");
        assertThatThrownBy(() -> service.report(foreign, resume.getId(), analysis.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(analyses).findByIdAndUserId(analysis.getId(), foreign);
        verifyNoInteractions(storage, extractor, model);
    }

    @Test void latestAndHistoryKeepParentAuthorizationAndDetachedReports() {
        var resume = resume("owned.pdf");
        var newer = ResumeAnalysis.create(owner, resume.getId(), Map.of("overallScore", 82), "new");
        var older = ResumeAnalysis.create(owner, resume.getId(), Map.of("overallScore", 70), "old");
        when(resumes.findByIdAndUserId(resume.getId(), owner)).thenReturn(Optional.of(resume));
        when(analyses.findByResumeIdAndUserId(resume.getId(), owner)).thenReturn(Optional.of(newer));
        when(analyses.findAllByResumeIdAndUserIdOrderByCreatedAtDesc(resume.getId(), owner)).thenReturn(List.of(newer, older));
        assertThat(service.analysisForResume(owner, resume.getId()).id()).isEqualTo(newer.getId());
        assertThat(service.analysisHistory(owner, resume.getId())).extracting(ResumeAnalysisSnapshot::id)
                .containsExactly(newer.getId(), older.getId());
        assertThatThrownBy(() -> service.analysisForResume(foreign, resume.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.analysisHistory(foreign, resume.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(analyses, never()).findByResumeIdAndUserId(resume.getId(), foreign);
        verify(analyses, never()).findAllByResumeIdAndUserIdOrderByCreatedAtDesc(resume.getId(), foreign);
        verifyNoInteractions(storage, extractor, model);
    }

    @Test void primaryAndDisplaySelectionAreAuthorizedAndPersisted() {
        var resume = resume("owned.pdf");
        when(resumes.findByIdAndUserId(resume.getId(), owner)).thenReturn(Optional.of(resume));
        service.setPrimary(owner, resume.getId());
        verify(resumes).clearPrimary(owner);
        assertThat(resume.isPrimary()).isTrue();

        var analysis = ResumeAnalysis.create(owner, resume.getId(), Map.of(), "fixture");
        when(analyses.findByIdAndUserId(analysis.getId(), owner)).thenReturn(Optional.of(analysis));
        service.showAnalysis(owner, resume.getId(), analysis.getId());
        assertThat(resume.getDisplayAnalysisId()).isEqualTo(analysis.getId());
        service.showAnalysis(owner, resume.getId(), null);
        assertThat(resume.getDisplayAnalysisId()).isNull();
        assertThatThrownBy(() -> service.showAnalysis(owner, resume.getId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setPrimary(foreign, resume.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void uploadSnapshotComesAfterExistingFlushAndDoesNotExposeSourceOrStorage() {
        when(storage.save(any(), eq(".pdf"))).thenReturn("PRIVATE_STORAGE_KEY");
        when(extractor.extract(any(), eq(".pdf"))).thenReturn("PRIVATE_SOURCE_TEXT");
        when(resumes.existsByUserIdAndPrimaryResumeTrue(owner)).thenReturn(true);
        when(resumes.saveAndFlush(any())).thenAnswer(call -> {
            Resume resume = call.getArgument(0);
            ReflectionTestUtils.invokeMethod(resume, resume.getCreatedAt() == null ? "onCreate" : "onUpdate");
            return resume;
        });
        var view = service.uploadView(owner, new MockMultipartFile("file", "fixture.pdf", "application/pdf", "%PDF-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(view.parseStatus()).isEqualTo(ResumeParseStatus.PARSED);
        assertThat(view.createdAt()).isNotNull();
        assertThat(view.analysisId()).isNull();
        assertThat(json.writeValueAsString(view)).doesNotContain("PRIVATE_SOURCE_TEXT", "PRIVATE_STORAGE_KEY");
        verify(resumes, times(2)).saveAndFlush(any());
        verify(storage, never()).delete(any());
        verifyNoInteractions(analyses, model);
    }

    @Test void httpKeepsResumeMetadataFieldsAndSixReportFieldsIncludingNull() {
        var resume = resume("owned.pdf");
        var view = ResumeView.from(resume, ResumeAnalysis.create(owner, resume.getId(), Map.of("overallScore", -2), null));
        var task = new ResumeAnalysisQueueService.TaskResult(UUID.randomUUID(), resume.getId(), ResumeAnalysisJobStatus.FAILED, null, 2, "SYNTHETIC", "failed");
        JsonNode wire = json.valueToTree(ResumeResponse.from(view, task));
        assertThat(wire.propertyNames()).containsExactly("id", "originalFilename", "contentType", "sizeBytes", "checksumSha256", "parseStatus", "createdAt", "analysisId", "analysisStatus", "analysisError", "overallScore", "primary", "displayAnalysisId");
        assertThat(wire.path("primary").asBoolean()).isFalse();
        assertThat(wire.path("displayAnalysisId").isNull()).isTrue();
        assertThat(wire.path("overallScore").asInt()).isZero();
        assertThat(wire.path("analysisStatus").asText()).isEqualTo("FAILED");
        assertThat(wire.path("analysisError").asText()).isEqualTo("failed");
        JsonNode empty = json.valueToTree(ResumeResponse.from(ResumeView.from(resume, null), null));
        for (String field : List.of("createdAt", "analysisId", "analysisStatus", "analysisError", "overallScore")) assertThat(empty.path(field).isNull()).as(field).isTrue();
        var report = ResumeAnalysisSnapshot.from(ResumeAnalysis.create(owner, resume.getId(), Map.of("overallScore", 82), null));
        JsonNode reportWire = json.valueToTree(ResumeAnalysisResponse.fromSnapshot(report));
        assertThat(reportWire.propertyNames()).containsExactly("id", "resumeId", "analysis", "modelName", "promptVersion", "createdAt");
    }

    @Test void controllerDelegatesOwnedSnapshotReadsInsteadOfDuplicatingParentChecks() {
        var application = mock(ResumeService.class); var queue = mock(ResumeAnalysisQueueService.class);
        var principal = mock(ArdorPrincipal.class); when(principal.userId()).thenReturn(owner);
        var resume = resume("owned.pdf"); var view = ResumeView.from(resume, null);
        var report = ResumeAnalysisSnapshot.from(ResumeAnalysis.create(owner, resume.getId(), Map.of(), "fixture"));
        when(application.listViews(owner)).thenReturn(List.of(view));
        when(application.report(owner, resume.getId(), report.id())).thenReturn(report);
        when(application.analysisHistory(owner, resume.getId())).thenReturn(List.of(report));
        var controller = new ResumeController(application, queue);
        assertThat(controller.list(principal)).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(resume.getId()));
        assertThat(controller.getAnalysis(principal, resume.getId(), report.id()).id()).isEqualTo(report.id());
        assertThat(controller.history(principal, resume.getId())).singleElement();
        verify(application).report(owner, resume.getId(), report.id());
        verify(application, never()).getAnalysisById(any(), any());
        verify(application, never()).getAnalysis(any(), any());
        verify(queue).statusIfPresent(owner, resume.getId());
    }

    @Test void applicationEntryPointsRetainTheirTransactionDeclarations() throws Exception {
        for (String name : List.of("listViews", "detail", "analysisForResume", "analysisHistory", "report")) {
            var method = Arrays.stream(ResumeService.class.getMethods()).filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class).readOnly()).as(name).isTrue();
        }
        assertThat(ResumeService.class.getMethod("uploadView", UUID.class, org.springframework.web.multipart.MultipartFile.class)
                .getAnnotation(Transactional.class).readOnly()).isFalse();
    }
}

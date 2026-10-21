package com.projectardor.materials.resume.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.repository.ResumeAnalysisRepository;
import com.projectardor.materials.resume.repository.ResumeRepository;
import com.projectardor.materials.resume.storage.ResumeStorage;
import com.projectardor.platform.model.llm.service.LlmGateway;
import tools.jackson.databind.ObjectMapper;

class ResumeFileAccessTests {
    private final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final ResumeStorage storage = mock(ResumeStorage.class);
    private final ResumeService service = new ResumeService(resumes, mock(ResumeAnalysisRepository.class), storage,
            mock(ResumeTextExtractor.class), mock(LlmGateway.class), mock(LlmJsonParser.class),
            new ObjectMapper(), mock(ResumeScoringStore.class));

    @Test void unknownOrForeignOwnerCannotReadOriginalOrPreviewBytes() {
        assertThatThrownBy(() -> service.file(owner, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.previewPage(owner, id, 1)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(storage);
    }

    @Test void ownedOriginalUsesItsStoredKeyAndPreservesFilenameAndBytes() {
        owned("合成简历.pdf");
        byte[] bytes = {1, 2, 3};
        when(storage.read("synthetic-key")).thenReturn(bytes);
        var file = service.file(owner, id);
        assertThat(file.filename()).isEqualTo("合成简历.pdf");
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.content()).containsExactly(bytes);
        verify(storage).read("synthetic-key");
    }

    @Test void pdfPreviewRendersSelectedPageAndRejectsInvalidPages() throws Exception {
        owned("synthetic.pdf");
        byte[] pdf;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.addPage(new PDPage());
            document.save(output); pdf = output.toByteArray();
        }
        when(storage.read("synthetic-key")).thenReturn(pdf);
        var preview = service.previewPage(owner, id, 2);
        assertThat(preview.pageNumber()).isEqualTo(2);
        assertThat(preview.pageCount()).isEqualTo(2);
        assertThat(preview.png()).startsWith(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        for (int page : new int[] {-1, 0, 3}) {
            assertThatThrownBy(() -> service.previewPage(owner, id, page)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void docxDoesNotEnterPdfRendererButRetainsOriginalDownload() {
        owned("synthetic.docx");
        assertThatThrownBy(() -> service.previewPage(owner, id, 1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
        when(storage.read("synthetic-key")).thenReturn(new byte[] {1});
        assertThat(service.file(owner, id).contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private void owned(String filename) {
        when(resumes.findByIdAndUserId(id, owner)).thenReturn(Optional.of(
                Resume.create(id, owner, filename, "application/octet-stream", "synthetic-key", 3, "0".repeat(64))));
    }
}

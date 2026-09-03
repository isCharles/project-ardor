package com.projectardor.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ResumeTextExtractorTests {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void extractsUtf8TextFromDocx() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("张三 Java 后端工程师，熟悉 Spring Boot");
            document.write(output);
            content = output.toByteArray();
        }

        assertThat(extractor.extract(content, ".docx"))
                .contains("张三", "Spring Boot");
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "acceptance-resume.docx"), content);
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> extractor.extract(new byte[] {1, 2, 3}, ".txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持 PDF 或 DOCX");
    }

    @Test
    void rejectsOrdinaryZipDisguisedAsDocx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("hello.txt"));
            zip.write("not a Word document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> extractor.extract(output.toByteArray(), ".docx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析或已损坏");
    }
}

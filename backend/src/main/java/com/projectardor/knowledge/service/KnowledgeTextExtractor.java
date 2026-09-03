package com.projectardor.knowledge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTextExtractor {
    private static final int MAX_PDF_PAGES = 200;
    private static final int MAX_CHARACTERS = 2_000_000;

    public String extract(byte[] content, String extension) {
        try {
            String text = switch (extension) {
                case ".pdf" -> extractPdf(content);
                case ".docx" -> extractDocx(content);
                case ".txt", ".md" -> decodeUtf8(content);
                default -> throw new IllegalArgumentException("只支持 PDF、DOCX、TXT 或 Markdown 文件");
            };
            String normalized = text.replace('\u0000', ' ').replace("\r\n", "\n").strip();
            if (normalized.isBlank()) throw new IllegalArgumentException("文件中没有可提取的文本");
            if (normalized.length() > MAX_CHARACTERS) throw new IllegalArgumentException("知识文档文本不能超过 200 万字符");
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("知识文档无法解析或已损坏", exception);
        }
    }

    private String extractPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF 页数不能超过 " + MAX_PDF_PAGES + " 页");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("TXT 和 Markdown 文件必须使用 UTF-8 编码", exception);
        }
    }
}

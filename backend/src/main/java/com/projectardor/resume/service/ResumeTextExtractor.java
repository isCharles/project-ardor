package com.projectardor.resume.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class ResumeTextExtractor {

    private static final int MAX_PDF_PAGES = 100;
    private static final int MAX_EXTRACTED_CHARACTERS = 1_000_000;

    public String extract(byte[] content, String extension) {
        try {
            String text = switch (extension) {
                case ".pdf" -> extractPdf(content);
                case ".docx" -> extractDocx(content);
                default -> throw new IllegalArgumentException("只支持 PDF 或 DOCX 简历");
            };
            String normalized = text.replace('\u0000', ' ').strip();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("简历中没有可提取的文本，请上传文本型 PDF 或 DOCX");
            }
            if (normalized.length() > MAX_EXTRACTED_CHARACTERS) {
                throw new IllegalArgumentException("简历文本过长，请精简后重新上传");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("简历文件无法解析或已损坏", exception);
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
}

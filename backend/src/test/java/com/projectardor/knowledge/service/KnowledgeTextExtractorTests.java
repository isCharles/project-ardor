package com.projectardor.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class KnowledgeTextExtractorTests {
    private final KnowledgeTextExtractor extractor = new KnowledgeTextExtractor();

    @Test
    void extractsUtf8TextAndMarkdown() {
        byte[] content = "虚拟线程适合高并发 I/O。".getBytes(StandardCharsets.UTF_8);

        assertThat(extractor.extract(content, ".md")).isEqualTo("虚拟线程适合高并发 I/O。");
    }

    @Test
    void rejectsNonUtf8PlainText() {
        assertThatThrownBy(() -> extractor.extract(new byte[] {(byte) 0xC3, (byte) 0x28}, ".txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
    }
}

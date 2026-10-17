package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.AgentMemoryItem;

class MemoryEmbeddingServiceTests {

    private static AgentMemoryItem item(String content) {
        return AgentMemoryItem.create(UUID.randomUUID(), content);
    }

    @Test
    void similarityDominatesThenImportanceAndRecency() {
        double similar = MemoryEmbeddingService.score(0.9, 1, 365);
        double lessSimilar = MemoryEmbeddingService.score(0.2, 5, 0);
        assertThat(similar).isGreaterThan(lessSimilar);

        double fresh = MemoryEmbeddingService.score(0.5, 3, 1);
        double stale = MemoryEmbeddingService.score(0.5, 3, 365);
        assertThat(fresh).isGreaterThan(stale);
    }

    @Test
    void clampsOutOfRangeInputs() {
        assertThat(MemoryEmbeddingService.score(1, 99, -5))
                .isEqualTo(MemoryEmbeddingService.score(1, 5, 0));
        assertThat(MemoryEmbeddingService.score(0, 0, 0)).isEqualTo(0.15);
    }

    @Test
    void rendersMemoriesWithinTheCharacterBudget() {
        String longLine = "a".repeat(1200);
        String rendered = MemoryEmbeddingService.render(List.of(
                item(longLine), item(longLine), item(longLine)));
        assertThat(rendered.length()).isLessThanOrEqualTo(2400);
        assertThat(rendered.startsWith("- a")).isTrue();
    }

    @Test
    void rendersNothingWhenThereAreNoMemories() {
        assertThat(MemoryEmbeddingService.render(List.of())).isEmpty();
    }

    @Test
    void serializesVectorsForPgvector() {
        assertThat(MemoryEmbeddingService.toLiteral(new float[]{1.5f, -2f}))
                .isEqualTo("[1.5,-2.0]");
    }
}

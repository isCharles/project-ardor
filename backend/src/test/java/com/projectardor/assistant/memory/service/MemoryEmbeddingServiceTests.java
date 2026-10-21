package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.memory.domain.AgentMemoryItem;

class MemoryEmbeddingServiceTests {

    @Test void pendingFactsRemainVisibleAlongsideIndexedMatches() {
        UUID owner = UUID.randomUUID();
        var old = AgentMemoryItem.create(owner, "Indexed old preference");
        var fresh = AgentMemoryItem.create(owner, "Just corrected preference");
        var memories = org.mockito.Mockito.mock(AgentMemoryService.class);
        var vectors = org.mockito.Mockito.mock(com.projectardor.assistant.memory.repository.MemoryVectorStore.class);
        var embeddings = org.mockito.Mockito.mock(com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService.class);
        org.mockito.Mockito.when(memories.validItems(owner)).thenReturn(List.of(old, fresh));
        org.mockito.Mockito.when(embeddings.isConfigured(owner)).thenReturn(true);
        org.mockito.Mockito.when(embeddings.embedQuery(owner, "question")).thenReturn(
                new com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector(new float[]{1, 0}, "fixture"));
        org.mockito.Mockito.when(vectors.similar(owner, "[1.0,0.0]", 2, "fixture", 24)).thenReturn(
                List.of(new com.projectardor.assistant.memory.repository.MemoryVectorStore.Match(old.getId(), 0.99)));
        org.mockito.Mockito.when(vectors.pendingForUser(owner, "fixture", 2)).thenReturn(
                List.of(new com.projectardor.assistant.memory.repository.MemoryVectorStore.Pending(fresh.getId(), fresh.getContent())));
        String context = new MemoryEmbeddingService(memories, vectors, embeddings).context(owner, "question");
        assertThat(context).startsWith("- Just corrected preference").contains("Indexed old preference");
    }

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

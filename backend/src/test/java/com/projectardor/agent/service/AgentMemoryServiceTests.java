package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.AgentMemoryItem;
import com.projectardor.agent.repository.AgentMemoryRepository;
import com.projectardor.agent.repository.AgentMemoryItemRepository;

class AgentMemoryServiceTests {

    @Test
    void editingOneMemoryPreservesItsIdAndDoesNotRecreateOtherItems() {
        UUID owner = UUID.randomUUID();
        AgentMemoryItem item = AgentMemoryItem.create(owner, "Java backend");
        when(itemRepository.findByIdAndUserId(item.getId(), owner)).thenReturn(java.util.Optional.of(item));
        when(itemRepository.saveAndFlush(item)).thenReturn(item);
        var result = service.edit(owner, item.getId(), "  Java and AI backend  ");
        assertThat(result.id()).isEqualTo(item.getId());
        assertThat(result.content()).isEqualTo("Java and AI backend");
        verify(itemRepository).invalidateEmbedding(owner, item.getId());
        verify(itemRepository, never()).deleteAllByUserId(owner);
        assertThatThrownBy(() -> service.edit(UUID.randomUUID(), item.getId(), "other owner"))
                .isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
    }

    @Test
    void duplicateMemoryEditDoesNotSilentlyMergeOrDelete() {
        UUID owner = UUID.randomUUID();
        AgentMemoryItem item = AgentMemoryItem.create(owner, "Original");
        when(itemRepository.findByIdAndUserId(item.getId(), owner)).thenReturn(java.util.Optional.of(item));
        when(itemRepository.existsByUserIdAndContentIgnoreCase(owner, "Duplicate")).thenReturn(true);
        assertThatThrownBy(() -> service.edit(owner, item.getId(), "Duplicate")).isInstanceOf(IllegalArgumentException.class);
        assertThat(item.getContent()).isEqualTo("Original");
        verify(itemRepository, never()).saveAndFlush(any());
    }

    private final AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
    private final AgentMemoryItemRepository itemRepository = mock(AgentMemoryItemRepository.class);
    private final AgentMemoryService service = new AgentMemoryService(repository, itemRepository);

    @Test void inferredConfidenceSurvivesPersistence() {
        UUID owner = UUID.randomUUID();
        when(itemRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var saved = service.add(owner, "Synthetic fact", "fact", "INFERRED", null, 0.4f);
        assertThat(saved.confidence()).isEqualTo(0.4f);
        assertThat(saved.sourceKind()).isEqualTo("INFERRED");
        assertThat(AgentMemoryItem.create(owner, "Explicit fact").getConfidence()).isEqualTo(1.0f);
        assertThatThrownBy(() -> AgentMemoryItem.create(owner, "Invalid fact", "fact", "INFERRED", null, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unchangedMemoryDoesNotInvalidateItsEmbedding() {
        UUID owner = UUID.randomUUID();
        var item = AgentMemoryItem.create(owner, "Unchanged");
        when(itemRepository.findByIdAndUserId(item.getId(), owner)).thenReturn(java.util.Optional.of(item));
        when(itemRepository.saveAndFlush(item)).thenReturn(item);
        service.edit(owner, item.getId(), "Unchanged");
        verify(itemRepository, never()).invalidateEmbedding(any(), any());
    }

    @Test void snapshotDeletesOnlyTheListedOwnedItems() {
        UUID owner = UUID.randomUUID();
        var old = AgentMemoryItem.create(owner, "Old");
        var newer = AgentMemoryItem.create(owner, "New");
        when(itemRepository.findByIdAndUserId(old.getId(), owner)).thenReturn(java.util.Optional.of(old));
        service.removeSnapshot(owner, List.of(old.getId(), old.getId()));
        verify(itemRepository).deleteAll(List.of(old));
        verify(itemRepository, never()).delete(newer);
        verify(itemRepository, never()).deleteAllByUserId(owner);
    }
    @Test void snapshotValidatesEveryTargetBeforeDeletingAnything() {
        UUID owner = UUID.randomUUID();
        var old = AgentMemoryItem.create(owner, "Old");
        when(itemRepository.findByIdAndUserId(old.getId(), owner)).thenReturn(java.util.Optional.of(old));
        assertThatThrownBy(() -> service.removeSnapshot(owner, List.of(old.getId(), UUID.randomUUID())))
                .isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void returnsEmptyMemoryForNewUser() {
        UUID userId = UUID.randomUUID();
        when(itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of());

        var result = service.get(userId);

        assertThat(result.content()).isEmpty();
        assertThat(result.updatedAt()).isNull();
    }

    @Test
    void createsTrimmedUserLevelMemory() {
        UUID userId = UUID.randomUUID();
        when(itemRepository.existsByUserIdAndContentIgnoreCase(userId, "目标岗位：Java 后端")).thenReturn(false);
        when(itemRepository.save(any(AgentMemoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.add(userId, "  目标岗位：Java 后端  ");

        assertThat(result.content()).isEqualTo("目标岗位：Java 后端");
        verify(itemRepository).save(any(AgentMemoryItem.class));
    }

    @Test
    void rejectsOversizedMemory() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), "a".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4000");
    }

    @Test
    void updatePreservesUnchangedMemoryItemInsteadOfRecreatingItsId() {
        UUID userId = UUID.randomUUID();
        AgentMemoryItem preserved = AgentMemoryItem.create(userId, "目标岗位：Java 后端");
        AgentMemoryItem removed = AgentMemoryItem.create(userId, "旧偏好");
        when(itemRepository.findAllByUserIdOrderByCreatedAtAsc(userId))
                .thenReturn(List.of(preserved, removed))
                .thenReturn(List.of(preserved));
        when(itemRepository.save(any(AgentMemoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.update(userId, "目标岗位：Java 后端\n偏好远程工作");

        verify(itemRepository, never()).delete(preserved);
        verify(itemRepository).delete(removed);
        verify(itemRepository).save(any(AgentMemoryItem.class));
    }
}

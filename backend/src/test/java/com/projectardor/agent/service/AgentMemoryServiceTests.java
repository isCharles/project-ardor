package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.AgentMemoryItem;
import com.projectardor.agent.repository.AgentMemoryRepository;
import com.projectardor.agent.repository.AgentMemoryItemRepository;

class AgentMemoryServiceTests {

    private final AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
    private final AgentMemoryItemRepository itemRepository = mock(AgentMemoryItemRepository.class);
    private final AgentMemoryService service = new AgentMemoryService(repository, itemRepository);

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
}

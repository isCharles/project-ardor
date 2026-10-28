package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.memory.domain.AgentMemoryItem;
import com.projectardor.assistant.memory.repository.AgentMemoryItemRepository;
import com.projectardor.assistant.memory.repository.AgentMemoryRepository;
import com.projectardor.assistant.memory.service.AgentMemoryItemView;
import com.projectardor.common.web.ResourceNotFoundException;

/** Pinning is what makes a memory resident, so it must stay a bounded, owner-scoped user action. */
class MemoryPinningServiceTests {

    private final AgentMemoryItemRepository items = mock(AgentMemoryItemRepository.class);
    private final AgentMemoryService service =
            new AgentMemoryService(mock(AgentMemoryRepository.class), items);
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach void persistReturnsTheSameRow() {
        when(items.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private AgentMemoryItem valid(String content) {
        return AgentMemoryItem.create(owner, content);
    }

    private void existing(AgentMemoryItem... stored) {
        when(items.findAllByUserIdOrderByCreatedAtAsc(owner)).thenReturn(List.of(stored));
    }

    @Test
    void theOwnerPinsAndUnpinsOneOfTheirOwnMemories() {
        var memory = valid("目标岗位：后端开发");
        when(items.findByIdAndUserId(memory.getId(), owner)).thenReturn(Optional.of(memory));
        existing(memory);

        AgentMemoryItemView pinned = service.setPinned(owner, memory.getId(), true);
        assertThat(pinned.pinned()).isTrue();
        assertThat(service.countPinned(owner)).isEqualTo(1);

        assertThat(service.setPinned(owner, memory.getId(), false).pinned()).isFalse();
        assertThat(service.countPinned(owner)).isZero();
    }

    @Test
    void anotherUsersMemoryIdResolvesToNothing() {
        var foreign = AgentMemoryItem.create(other, "别的用户的记忆");
        when(items.findByIdAndUserId(foreign.getId(), owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPinned(owner, foreign.getId(), true))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void onlyAFewMemoriesMayStayResident() {
        var picked = new AgentMemoryItem[AgentMemoryService.MAX_PINNED];
        for (int index = 0; index < picked.length; index++) {
            picked[index] = valid("核心 " + index);
            picked[index].pin(true);
            when(items.findByIdAndUserId(picked[index].getId(), owner)).thenReturn(Optional.of(picked[index]));
        }
        existing(picked);
        var extra = valid("想再固定一条");
        when(items.findByIdAndUserId(extra.getId(), owner)).thenReturn(Optional.of(extra));

        assertThatThrownBy(() -> service.setPinned(owner, extra.getId(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AgentMemoryService.MAX_PINNED));
        assertThat(extra.isPinned()).isFalse();
        // Releasing a core memory never needs the cap, so the panel can always shrink.
        assertThat(service.setPinned(owner, picked[0].getId(), false).pinned()).isFalse();
    }

    @Test
    void aSupersededMemoryCannotBecomeCoreAgain() {
        var stale = valid("已更正的旧目标");
        stale.invalidate(null, UUID.randomUUID());
        when(items.findByIdAndUserId(stale.getId(), owner)).thenReturn(Optional.of(stale));
        existing(stale);

        assertThatThrownBy(() -> service.setPinned(owner, stale.getId(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stale.isPinned()).isFalse();
    }
}

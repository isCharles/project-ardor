package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.projectardor.assistant.memory.domain.AgentMemoryItem;
import com.projectardor.assistant.memory.repository.AgentMemoryItemRepository;
import com.projectardor.assistant.memory.repository.AgentMemoryRepository;
import com.projectardor.assistant.memory.web.AgentMemoryItemResponse;
import com.projectardor.assistant.memory.web.AgentMemoryResponse;
import tools.jackson.databind.json.JsonMapper;

class AgentMemoryViewTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void summaryKeepsExactLegacyJsonIncludingNullTimestamp() {
        for (var view : List.of(new AgentMemoryView("", null), new AgentMemoryView("合成偏好", Instant.EPOCH))) {
            String legacy = mapper.writeValueAsString(new AgentMemoryResponse(view.content(), view.updatedAt()));
            assertThat(mapper.writeValueAsString(view)).isEqualTo(legacy);
            assertThat(mapper.writeValueAsString(AgentMemoryResponse.from(view))).isEqualTo(legacy);
            assertThat(mapper.readTree(legacy).propertyNames()).containsExactly("content", "updatedAt");
        }
    }

    @Test void itemKeepsSourceConfidenceValidityAndLegacyJsonWithoutInternalProvenanceIds() {
        var item = AgentMemoryItem.create(UUID.randomUUID(), "Synthetic preference", "preference", "INFERRED", UUID.randomUUID(), 0.7f);
        ReflectionTestUtils.setField(item, "createdAt", Instant.EPOCH);
        ReflectionTestUtils.setField(item, "updatedAt", Instant.EPOCH.plusSeconds(1));
        var view = AgentMemoryItemView.from(item);
        var legacy = new AgentMemoryItemResponse(item.getId(), "Synthetic preference", "preference", 0.7f,
                item.getImportance(), "INFERRED", item.getValidFrom(), null, true, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        String expected = mapper.writeValueAsString(legacy);
        assertThat(mapper.writeValueAsString(view)).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(AgentMemoryItemResponse.from(view))).isEqualTo(expected);
        assertThat(mapper.writeValueAsString(AgentMemoryItemResponse.from(item))).isEqualTo(expected);
        assertThat(mapper.readTree(expected).propertyNames()).containsExactly("id", "content", "kind", "confidence", "importance",
                "sourceKind", "validFrom", "validUntil", "valid", "createdAt", "updatedAt");
        item.invalidate(Instant.EPOCH.plusSeconds(2), UUID.randomUUID());
        assertThat(view.valid()).isTrue();
        assertThat(AgentMemoryItemView.from(item).valid()).isFalse();
        assertThat(AgentMemoryItemView.from(item).validUntil()).isEqualTo(Instant.EPOCH.plusSeconds(2));
    }

    @Test void currentSummaryAndItemHistoryRetainDifferentMeaningsAndOwner() {
        UUID owner = UUID.randomUUID();
        var old = AgentMemoryItem.create(owner, "Old");
        var current = AgentMemoryItem.create(owner, "Current");
        old.invalidate(Instant.now(), current.getId());
        ReflectionTestUtils.setField(current, "updatedAt", Instant.EPOCH);
        var repository = mock(AgentMemoryItemRepository.class);
        when(repository.findAllByUserIdOrderByCreatedAtAsc(owner)).thenReturn(List.of(old, current));
        var service = new AgentMemoryService(mock(AgentMemoryRepository.class), repository);
        assertThat(service.get(owner)).isEqualTo(new AgentMemoryView("Current", Instant.EPOCH));
        assertThat(service.listItems(owner)).extracting(AgentMemoryItemView::valid).containsExactly(false, true);
        assertThat(service.listItems(UUID.randomUUID())).isEmpty();
        assertThat(service.content(owner)).isEqualTo("- Current");
    }
}

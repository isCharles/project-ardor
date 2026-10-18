package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.AgentMemoryItem;

class MemoryMaintenanceServiceTests {

    @Test
    void expiresOnlyOldLowConfidenceInferredFacts() {
        Instant now = Instant.now();
        assertThat(MemoryMaintenanceService.shouldExpire("INFERRED", 0.4,
                now.minus(90, ChronoUnit.DAYS), null, now)).isTrue();
        assertThat(MemoryMaintenanceService.shouldExpire("INFERRED", 0.4,
                now.minus(5, ChronoUnit.DAYS), null, now)).isFalse();
        assertThat(MemoryMaintenanceService.shouldExpire("INFERRED", 0.8,
                now.minus(90, ChronoUnit.DAYS), null, now)).isFalse();
        assertThat(MemoryMaintenanceService.shouldExpire("USER_EXPLICIT", 0.4,
                now.minus(90, ChronoUnit.DAYS), null, now)).isFalse();
        assertThat(MemoryMaintenanceService.shouldExpire(AgentMemoryItem.create(UUID.randomUUID(), "用户说的事实",
                "fact", "USER_EXPLICIT", null), now)).isFalse();
    }

    @Test
    void containmentAloneCannotSupersedeANewerOrDifferentFact() {
        AgentMemoryItem longer = AgentMemoryItem.create(UUID.randomUUID(), "用户是杨志，北京邮电大学电子信息专业硕士研究生二年级（三年制）。");
        AgentMemoryItem shorter = AgentMemoryItem.create(UUID.randomUUID(), "北京邮电大学电子信息专业硕士研究生二年级");
        AgentMemoryItem unrelated = AgentMemoryItem.create(UUID.randomUUID(), "用户喜欢简洁直接的反馈");

        var pairs = MemoryMaintenanceService.mergeCandidates(List.of(longer, shorter, unrelated));

        assertThat(pairs).isEmpty();
    }

    @Test
    void ignoresVeryShortFacts() {
        AgentMemoryItem longer = AgentMemoryItem.create(UUID.randomUUID(), "用户的目标岗位是 Java 后端开发");
        AgentMemoryItem tiny = AgentMemoryItem.create(UUID.randomUUID(), "Java");
        assertThat(MemoryMaintenanceService.mergeCandidates(List.of(longer, tiny))).isEmpty();
    }

    @Test
    void neverDropsOneFactTwice() {
        AgentMemoryItem keep = AgentMemoryItem.create(UUID.randomUUID(), "用户的目标是字节跳动 Java 后端实习");
        AgentMemoryItem middle = AgentMemoryItem.create(UUID.randomUUID(), "目标是字节跳动 Java 后端实习");
        AgentMemoryItem shortest = AgentMemoryItem.create(UUID.randomUUID(), "字节跳动 Java 后端实习");
        var pairs = MemoryMaintenanceService.mergeCandidates(List.of(keep, middle, shortest));
        assertThat(pairs).allSatisfy(pair -> assertThat(pair.dropId()).isNotEqualTo(keep.getId()));
        assertThat(pairs.stream().map(MemoryMaintenanceService.MergePair::dropId).distinct().count())
                .isEqualTo(pairs.size());
    }

    @Test void exactDuplicatesKeepTheNewestFactAndDoNotMixProvenance() {
        UUID owner = UUID.randomUUID();
        var older = AgentMemoryItem.create(owner, "Same explicit preference");
        var newer = AgentMemoryItem.create(owner, "Same explicit preference");
        var inferred = AgentMemoryItem.create(owner, "Same explicit preference", "fact", "INFERRED", null);
        org.springframework.test.util.ReflectionTestUtils.setField(older, "validFrom", Instant.parse("2025-01-01T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(newer, "validFrom", Instant.parse("2026-01-01T00:00:00Z"));
        var pairs = MemoryMaintenanceService.mergeCandidates(List.of(older, newer, inferred));
        assertThat(pairs).containsExactly(new MemoryMaintenanceService.MergePair(newer.getId(), older.getId()));
        assertThat(MemoryMaintenanceService.mergeCandidates(List.of(
                AgentMemoryItem.create(owner, "Budget is 1.2 yuan"), AgentMemoryItem.create(owner, "Budget is 12 yuan")))).isEmpty();
    }

    @Test void maintenanceVisitsTheNextPageInsteadOfStarvingUsersAfterFifty() {
        var store = org.mockito.Mockito.mock(com.projectardor.agent.repository.MemoryVectorStore.class);
        var memories = org.mockito.Mockito.mock(AgentMemoryService.class);
        var first = java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(i -> new UUID(0, i)).toList();
        UUID last = new UUID(0, 51);
        org.mockito.Mockito.when(store.usersWithMemoriesAfter(null, 50)).thenReturn(first);
        org.mockito.Mockito.when(store.usersWithMemoriesAfter(first.getLast(), 50)).thenReturn(List.of(last));
        new MemoryMaintenanceService(store, memories).maintain();
        org.mockito.Mockito.verify(store).expireLowConfidence(last, 0.5, 60);
        org.mockito.Mockito.verify(memories).validItems(last);
    }
}

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
    void mergesAFullyContainedShorterFactIntoTheLongerOne() {
        AgentMemoryItem longer = AgentMemoryItem.create(UUID.randomUUID(), "用户是杨志，北京邮电大学电子信息专业硕士研究生二年级（三年制）。");
        AgentMemoryItem shorter = AgentMemoryItem.create(UUID.randomUUID(), "北京邮电大学电子信息专业硕士研究生二年级");
        AgentMemoryItem unrelated = AgentMemoryItem.create(UUID.randomUUID(), "用户喜欢简洁直接的反馈");

        var pairs = MemoryMaintenanceService.mergeCandidates(List.of(longer, shorter, unrelated));

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).keepId()).isEqualTo(longer.getId());
        assertThat(pairs.get(0).dropId()).isEqualTo(shorter.getId());
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
}

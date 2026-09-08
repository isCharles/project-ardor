package com.projectardor.recap.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryCardTests {
    @Test
    void schedulesGoodReviewsWithIncreasingIntervals() {
        Instant now = Instant.parse("2026-09-02T08:00:00Z");
        MemoryCard card = MemoryCard.create(UUID.randomUUID(), null, MemoryCardSource.INTERVIEW,
                "面试原题", null, "Redis 为什么快？", "内存、数据结构与事件模型", List.of("Redis"), now);

        assertThat(card.review(MemoryCardRating.GOOD, now).nextIntervalDays()).isEqualTo(1);
        assertThat(card.review(MemoryCardRating.GOOD, now).nextIntervalDays()).isEqualTo(3);
        assertThat(card.review(MemoryCardRating.GOOD, now).nextIntervalDays()).isGreaterThanOrEqualTo(7);
        assertThat(card.getStatus()).isEqualTo(MemoryCardStatus.REVIEW);
    }

    @Test
    void againResetsProgressAndRecordsLapse() {
        Instant now = Instant.parse("2026-09-02T08:00:00Z");
        MemoryCard card = MemoryCard.create(UUID.randomUUID(), null, MemoryCardSource.AGENT,
                "训练题", null, "问题", "答案", List.of(), now);
        card.review(MemoryCardRating.GOOD, now);
        card.review(MemoryCardRating.AGAIN, now);

        assertThat(card.getIntervalDays()).isEqualTo(1);
        assertThat(card.getRepetitions()).isZero();
        assertThat(card.getLapses()).isEqualTo(1);
        assertThat(card.getStatus()).isEqualTo(MemoryCardStatus.LEARNING);
    }

    @Test
    void editsContentWithoutResettingReviewProgress() {
        Instant now = Instant.parse("2026-09-02T08:00:00Z");
        MemoryCard card = MemoryCard.create(UUID.randomUUID(), null, MemoryCardSource.AGENT,
                "训练题", null, "旧问题", "旧答案", List.of("旧标签"), now);
        card.review(MemoryCardRating.GOOD, now);

        card.edit("介绍 JVM 的核心机制", "类加载、运行时内存和执行引擎", List.of("JVM", "Java"));

        assertThat(card.getFront()).isEqualTo("介绍 JVM 的核心机制");
        assertThat(card.getBack()).contains("类加载");
        assertThat(card.getTags()).containsExactly("JVM", "Java");
        assertThat(card.getRepetitions()).isEqualTo(1);
    }
}

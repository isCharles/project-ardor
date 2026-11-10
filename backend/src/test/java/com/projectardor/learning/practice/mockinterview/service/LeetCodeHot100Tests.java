package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LeetCodeHot100Tests {

    @Test
    void catalogHasOneHundredDistinctProblems() {
        assertThat(LeetCodeHot100.PROBLEMS).hasSize(100);
        Set<Integer> ids = new HashSet<>();
        Set<String> slugs = new HashSet<>();
        LeetCodeHot100.PROBLEMS.forEach(problem -> {
            ids.add(problem.id());
            slugs.add(problem.slug());
            assertThat(problem.slug()).matches("[a-z0-9-]+").hasSizeLessThanOrEqualTo(100);
            assertThat(problem.titleEn()).isNotBlank();
            assertThat(problem.titleZh()).isNotBlank();
        });
        assertThat(ids).hasSize(100);
        assertThat(slugs).hasSize(100);
    }

    @Test
    void randomPicksComeFromTheCatalogAndFindResolvesThem() {
        LeetCodeHot100 hot100 = new LeetCodeHot100();
        for (int i = 0; i < 200; i++) {
            LeetCodeHot100.Problem problem = hot100.random();
            assertThat(LeetCodeHot100.find(problem.slug())).contains(problem);
        }
        assertThat(LeetCodeHot100.find(null)).isEmpty();
        assertThat(LeetCodeHot100.find("not-a-problem")).isEmpty();
    }

    @Test
    void everyProblemBelongsToExactlyOneTopic() {
        LeetCodeHot100 hot100 = new LeetCodeHot100();
        Set<String> grouped = new HashSet<>();
        for (LeetCodeHot100.Topic topic : LeetCodeHot100.TOPICS) {
            assertThat(topic.name()).isNotBlank();
            assertThat(topic.slugs()).isNotEmpty().doesNotHaveDuplicates();
            for (String slug : topic.slugs()) {
                assertThat(grouped.add(slug)).as("题目只属于一个分组：%s", slug).isTrue();
                assertThat(hot100.byTopic(topic.name())).contains(LeetCodeHot100.find(slug).orElseThrow());
            }
        }
        assertThat(grouped).containsExactlyInAnyOrderElementsOf(
                LeetCodeHot100.PROBLEMS.stream().map(LeetCodeHot100.Problem::slug).toList());
        assertThat(hot100.byTopic(null)).isEmpty();
        assertThat(hot100.byTopic("不存在的分组")).isEmpty();
    }

    @Test
    void aCodingTopicHoldsEnoughProblemsToOutlastThePerPointTurnBudget() {
        LeetCodeHot100 hot100 = new LeetCodeHot100();
        assertThat(LeetCodeHot100.CODING_TOPICS).isNotEmpty().doesNotHaveDuplicates();
        for (String topic : LeetCodeHot100.CODING_TOPICS) {
            assertThat(hot100.byTopic(topic)).hasSizeGreaterThanOrEqualTo(3);
        }
        // 滑动窗口 only holds two problems, so it cannot sustain three turns without a repeat.
        assertThat(LeetCodeHot100.CODING_TOPICS).doesNotContain("滑动窗口");
        assertThat(hot100.byTopic("滑动窗口")).hasSize(2);
    }
}

package com.projectardor.interview.service;

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
}

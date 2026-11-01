package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentToolResultBudgetTests {
    @Test void resultsInsideTheCeilingAreReturnedUntouched() {
        String result = "a".repeat(100);

        assertThat(AgentToolResultBudget.bound(result, "read_resume", "call-1", 500)).isSameAs(result);
    }

    @Test void anOversizedResultKeepsItsHeadAndTailAndNamesTheCallItCameFrom() {
        String result = "H".repeat(1_000) + "M".repeat(4_000) + "T".repeat(1_000);

        String bounded = AgentToolResultBudget.bound(result, "fetch_web_page", "call-7", 1_000);

        assertThat(bounded).startsWith("H".repeat(750)).endsWith("T".repeat(250));
        assertThat(bounded).doesNotContain("M".repeat(10));
        assertThat(bounded).contains("fetch_web_page").contains("call-7").contains("6000 字符");
        assertThat(bounded).contains("不得声称已读取被省略的部分");
        assertThat(bounded.length()).isLessThan(result.length());
    }

    @Test void cuttingBetweenSurrogatePairsWouldReachTheProviderAsAnInvalidCharacter() {
        String result = "a" + "😀".repeat(600) + "b";

        String bounded = AgentToolResultBudget.bound(result, "read_document", "call-1", 200);

        assertThat(loneSurrogates(bounded)).as("no lone surrogate half").isZero();
        assertThat(bounded).doesNotContain("\uFFFD");
    }

    /** Pairs are legal and expected; only an unpaired half is what a provider rejects. */
    private static int loneSurrogates(String value) {
        int lone = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) lone++;
                else index++;
            } else if (Character.isLowSurrogate(current)) lone++;
        }
        return lone;
    }

    @Test void anUnsetCeilingStillBoundsTheResultInsteadOfLettingItThrough() {
        assertThat(AgentToolResultBudget.resolve(0)).isEqualTo(AgentToolResultBudget.DEFAULT_MAX_CHARS);
        assertThat(AgentToolResultBudget.bound("x".repeat(20_000), "list_jobs", "call-1", 0).length())
                .isLessThan(20_000);
    }

    @Test void aNullResultIsPassedThroughForTheCallerToReport() {
        assertThat(AgentToolResultBudget.bound(null, "list_jobs", "call-1", 100)).isNull();
    }
}

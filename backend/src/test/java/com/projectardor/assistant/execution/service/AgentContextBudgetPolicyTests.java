package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class AgentContextBudgetPolicyTests {
    @Test void onePercentDoesNotTriggerCompressionAndOutputIsReservedOnlyOnce() {
        var capacity = mock(ModelContextCapacity.class);
        when(capacity.resolve(null)).thenReturn(1048576L);
        var budget = new AgentContextBudgetPolicy(capacity, 12000, .75, .05).resolve(null, 4096);
        assertThat(budget.shouldCompact(12784)).isFalse();
        assertThat(budget.hardInputTokens()).isEqualTo(1048576L - 4096 - 52429);
        assertThat(budget.shouldCompact(budget.softInputTokens())).isTrue();
        assertThat(budget.exceedsHardLimit(budget.hardInputTokens())).isFalse();
        assertThat(budget.exceedsHardLimit(budget.hardInputTokens() + 1)).isTrue();
    }

    @Test void unknownCapacityUsesTwoHundredThousandTokenApplicationBudgetWithoutInventingAWindow() {
        var capacity = mock(ModelContextCapacity.class);
        var budget = new AgentContextBudgetPolicy(capacity, 200000, .75, .05).resolve(null, 4096);
        assertThat(budget.windowTokens()).isNull();
        assertThat(budget.hardInputTokens()).isNull();
        assertThat(budget.shouldCompact(199999)).isFalse();
        assertThat(budget.shouldCompact(200000)).isTrue();
    }

    @Test void unknownCapacityStillSupportsAnExplicitSmallerApplicationBudget() {
        var capacity = mock(ModelContextCapacity.class);
        var budget = new AgentContextBudgetPolicy(capacity, 12000, .75, .05).resolve(null, 4096);
        assertThat(budget.shouldCompact(11999)).isFalse();
        assertThat(budget.shouldCompact(12000)).isTrue();
    }

    @Test void impossibleOrInvalidBudgetsCannotSilentlyAllowAnOversizedRequest() {
        var capacity = mock(ModelContextCapacity.class);
        when(capacity.resolve(null)).thenReturn(2000L);
        var budget = new AgentContextBudgetPolicy(capacity, 12000, .75, .05).resolve(null, 4096);
        assertThat(budget.exceedsHardLimit(1)).isTrue();
        assertThatThrownBy(() -> new AgentContextBudgetPolicy(capacity, 12000, Double.NaN, .05))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentContextBudgetPolicy(capacity, 12000, 1, .05))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ownerScopedCapacityDrivesTheSoftAndHardBudgets() {
        var capacity = mock(ModelContextCapacity.class);
        var user = java.util.UUID.randomUUID();
        var config = new com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig(
                com.projectardor.platform.model.llm.domain.LlmProvider.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "deepseek-v4-flash", "synthetic");
        when(capacity.resolve(user, config)).thenReturn(200_000L);

        var budget = new AgentContextBudgetPolicy(capacity, 200000, .75, .05).resolve(user, config, 4096);

        assertThat(budget.windowTokens()).isEqualTo(200_000L);
        assertThat(budget.safetyReserveTokens()).isEqualTo(10_000L);
        assertThat(budget.hardInputTokens()).isEqualTo(200_000L - 10_000L - 4096L);
        assertThat(budget.softInputTokens()).isEqualTo((long) Math.floor((200_000L - 10_000L - 4096L) * .75));
    }
}

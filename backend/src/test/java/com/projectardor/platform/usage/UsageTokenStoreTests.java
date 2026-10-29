package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class UsageTokenStoreTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final MembershipPlanService plans = mock(MembershipPlanService.class);
    private final UsagePackService packs = mock(UsagePackService.class);
    private final UsageTokenStore store = new UsageTokenStore(jdbc, memberships, plans, packs);

    @Test
    void thePlanBudgetIsChargedBeforePackTokens() {
        UUID user = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(jdbc.queryForObject(contains("SUM(total_tokens - pack_tokens)"), eq(Long.class), any(), any()))
                .thenReturn(90L);
        when(packs.balance(user)).thenReturn(500L);

        store.record(user, UsageFeature.AGENT_CHAT, requestId, 20, 10, "synthetic-model");

        assertThat(insertedPackTokens()).as("only the 20 tokens beyond the 10 remaining plan tokens use a pack")
                .isEqualTo(20L);
    }

    @Test
    void tokensStayOnThePlanWhileTheMonthlyBudgetLasts() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(jdbc.queryForObject(contains("SUM(total_tokens - pack_tokens)"), eq(Long.class), any(), any()))
                .thenReturn(10L);
        when(packs.balance(user)).thenReturn(500L);

        store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 20, 10, null);

        assertThat(insertedPackTokens()).isEqualTo(0L);
    }

    @Test
    void packsAreNotTouchedWhenThePlanDefinesNoTokenBudget() {
        UUID user = UUID.randomUUID();
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(plans.tokenLimit(MembershipTier.FREE)).thenReturn(0L);

        store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 20, 10, null);

        assertThat(insertedPackTokens()).isEqualTo(0L);
        verify(packs, org.mockito.Mockito.never()).balance(user);
    }

    private Object insertedPackTokens() {
        var packTokens = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("INSERT INTO usage_token_ledger"), any(), any(), any(), any(), any(),
                any(), any(), packTokens.capture(), any());
        return packTokens.getValue();
    }
}

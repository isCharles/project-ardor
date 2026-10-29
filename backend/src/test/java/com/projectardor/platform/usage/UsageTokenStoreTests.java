package com.projectardor.platform.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class UsageTokenStoreTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final MembershipPlanService plans = mock(MembershipPlanService.class);
    private final UsagePackService packs = mock(UsagePackService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UsageTokenStore store = new UsageTokenStore(jdbc, memberships, plans, packs, transactions);

    private void transactionCommits() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void thePlanBudgetIsChargedBeforePackTokens() {
        UUID user = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        transactionCommits();
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
        transactionCommits();
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
        transactionCommits();
        when(memberships.tier(user)).thenReturn(MembershipTier.FREE);
        when(plans.tokenLimit(MembershipTier.FREE)).thenReturn(0L);

        store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 20, 10, null);

        assertThat(insertedPackTokens()).isEqualTo(0L);
        verify(packs, org.mockito.Mockito.never()).balance(user);
    }

    @Test
    void theUserLockIsHeldBeforeThePackBalanceIsReadAndTheLedgerIsWritten() {
        UUID user = UUID.randomUUID();
        transactionCommits();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(jdbc.queryForObject(contains("SUM(total_tokens - pack_tokens)"), eq(Long.class), any(), any()))
                .thenReturn(90L);
        when(packs.balance(user)).thenReturn(500L);

        store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 20, 10, "synthetic-model");

        InOrder order = inOrder(jdbc, packs);
        order.verify(jdbc).queryForList(startsWith("SELECT id FROM users"), eq(UUID.class), eq(user));
        order.verify(packs).balance(user);
        order.verify(jdbc).update(startsWith("INSERT INTO usage_token_ledger"), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void aPackSplitFailureStillRecordsTheUsageAgainstThePlan() {
        UUID user = UUID.randomUUID();
        transactionCommits();
        when(memberships.tier(user)).thenReturn(MembershipTier.PLUS);
        when(plans.tokenLimit(MembershipTier.PLUS)).thenReturn(100L);
        when(jdbc.queryForObject(contains("SUM(total_tokens - pack_tokens)"), eq(Long.class), any(), any()))
                .thenReturn(90L);
        when(packs.balance(user)).thenThrow(new IllegalStateException("synthetic pack failure"));

        store.record(user, UsageFeature.AGENT_CHAT, UUID.randomUUID(), 20, 10, "synthetic-model");

        assertThat(insertedPackTokens()).isEqualTo(0L);
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    private Object insertedPackTokens() {
        var packTokens = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(startsWith("INSERT INTO usage_token_ledger"), any(), any(), any(), any(), any(),
                any(), any(), packTokens.capture(), any());
        return packTokens.getValue();
    }
}

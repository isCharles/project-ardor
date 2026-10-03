package com.projectardor.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MembershipServiceTests {
    @Test
    void newUsersReceiveGiftedMembership() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();

        new MembershipService(jdbc).grantWelcomeMembership(userId);

        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat((String sql) ->
                sql.contains("'MEMBER'") && sql.contains("'GIFT'")
                        && sql.contains("ON CONFLICT (user_id) DO NOTHING")), eq(userId));
    }

    @Test
    void administratorCanWithdrawGiftWithoutDeletingTheAccount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(userId))).thenReturn(1);

        MembershipTier result = new MembershipService(jdbc).setTier(userId, MembershipTier.FREE);

        assertThat(result).isEqualTo(MembershipTier.FREE);
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat((String sql) ->
                sql.contains("grant_source") && sql.contains("'ADMIN'")),
                eq(userId), eq("FREE"));
    }
}

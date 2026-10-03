package com.projectardor.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.repository.UserAccountRepository;
import com.projectardor.usage.MembershipService;
import com.projectardor.usage.MembershipTier;

class AdminUserServiceTests {
    @Test
    void listsMembershipsWithOneBatchLookup() {
        UserAccountRepository repository = mock(UserAccountRepository.class);
        MembershipService memberships = mock(MembershipService.class);
        UserAccount first = UserAccount.create("first@example.com", "hash");
        UserAccount second = UserAccount.create("second@example.com", "hash");
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(first, second));
        when(memberships.tiers(List.of(first.getId(), second.getId())))
                .thenReturn(Map.of(first.getId(), MembershipTier.MEMBER));

        var users = new AdminUserService(repository, memberships).list();

        assertThat(users).extracting(user -> user.membership())
                .containsExactly(MembershipTier.MEMBER, MembershipTier.FREE);
        verify(memberships).tiers(List.of(first.getId(), second.getId()));
        verify(memberships, never()).tier(org.mockito.ArgumentMatchers.any());
    }
}

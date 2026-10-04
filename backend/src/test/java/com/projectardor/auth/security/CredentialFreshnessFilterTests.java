package com.projectardor.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.repository.UserAccountRepository;

import jakarta.servlet.FilterChain;

class CredentialFreshnessFilterTests {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final CredentialFreshnessFilter filter = new CredentialFreshnessFilter(users);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnOldSessionAfterThePasswordChanges() throws Exception {
        UserAccount user = UserAccount.create("test@example.com", "new-hash");
        ArdorPrincipal principal = new ArdorPrincipal(user.getId(), user.getEmail(), "old-hash", true, UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void acceptsARecentlyAuthenticatedSession() throws Exception {
        UserAccount user = UserAccount.create("test@example.com", "current-hash");
        ArdorPrincipal principal = new ArdorPrincipal(user.getId(), user.getEmail(), "current-hash", true, UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}

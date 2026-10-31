package com.projectardor.platform.identity.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.profile.repository.UserProfileRepository;
import com.projectardor.platform.usage.MembershipService;

class RegistrationPolicyTests {

    @Test
    void emptyAllowlistKeepsOpenRegistration() {
        assertThatCode(() -> new RegistrationPolicy("").requireAllowed("anyone@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void configuredAllowlistIsCaseInsensitiveAndTrimmed() {
        RegistrationPolicy policy = new RegistrationPolicy(" Trial@Example.com , second@example.com ");
        assertThatCode(() -> policy.requireAllowed("trial@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAllowed("second@example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireAllowed("other@example.com"))
                .isInstanceOf(RegistrationNotAllowedException.class);
    }

    @Test
    void registrationStopsBeforeAnyAccountLookupWhenEmailIsNotAllowed() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        AuthService service = new AuthService(users, mock(UserProfileRepository.class),
                new BCryptPasswordEncoder(4), mock(MembershipService.class),
                new RegistrationPolicy("allowed@example.com"));

        assertThatThrownBy(() -> service.register("blocked@example.com", "password-123", null))
                .isInstanceOf(RegistrationNotAllowedException.class);
        verify(users, never()).existsByEmail(anyString());
    }

    @Test
    void allowedEmailStillFollowsTheExistingDuplicateGuard() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.existsByEmail("allowed@example.com")).thenReturn(true);
        AuthService service = new AuthService(users, mock(UserProfileRepository.class),
                new BCryptPasswordEncoder(4), mock(MembershipService.class),
                new RegistrationPolicy("allowed@example.com"));

        assertThatThrownBy(() -> service.register("Allowed@Example.com", "password-123", null))
                .isInstanceOf(DuplicateEmailException.class);
    }
}

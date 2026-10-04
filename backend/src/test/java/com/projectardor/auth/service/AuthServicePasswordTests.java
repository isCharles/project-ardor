package com.projectardor.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.repository.UserAccountRepository;
import com.projectardor.profile.repository.UserProfileRepository;
import com.projectardor.usage.MembershipService;

class AuthServicePasswordTests {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthService service = new AuthService(
            users, mock(UserProfileRepository.class), encoder, mock(MembershipService.class));

    @Test
    void changesPasswordOnlyAfterMatchingCurrentPassword() {
        UserAccount user = UserAccount.create("test@example.com", encoder.encode("old-password"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        service.changePassword(user.getId(), "old-password", "new-password");

        assertThat(encoder.matches("new-password", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-password", user.getPasswordHash())).isFalse();
    }

    @Test
    void rejectsIncorrectCurrentPasswordWithoutChangingStoredHash() {
        UserAccount user = UserAccount.create("test@example.com", encoder.encode("old-password"));
        String originalHash = user.getPasswordHash();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(user.getId(), "wrong-password", "new-password"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("当前密码不正确");
        assertThat(user.getPasswordHash()).isEqualTo(originalHash);
    }

    @Test
    void rejectsReusingCurrentPassword() {
        UserAccount user = UserAccount.create("test@example.com", encoder.encode("old-password"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changePassword(user.getId(), "old-password", "old-password"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("新密码不能与当前密码相同");
    }
}

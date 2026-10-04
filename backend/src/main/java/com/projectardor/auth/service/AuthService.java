package com.projectardor.auth.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.repository.UserAccountRepository;
import com.projectardor.profile.domain.UserProfile;
import com.projectardor.profile.repository.UserProfileRepository;
import com.projectardor.usage.MembershipService;

@Service
public class AuthService {

    private final UserAccountRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final MembershipService memberships;

    public AuthService(
            UserAccountRepository userRepository,
            UserProfileRepository profileRepository,
            PasswordEncoder passwordEncoder,
            MembershipService memberships) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.passwordEncoder = passwordEncoder;
        this.memberships = memberships;
    }

    @Transactional
    public UserAccount register(String rawEmail, String rawPassword, String displayName) {
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        try {
            UserAccount user = userRepository.saveAndFlush(
                    UserAccount.create(email, passwordEncoder.encode(rawPassword)));
            profileRepository.save(UserProfile.create(user.getId(), normalizeDisplayName(displayName)));
            memberships.grantWelcomeMembership(user.getId());
            return user;
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException();
        }
    }

    public String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return displayName.strip();
    }
}


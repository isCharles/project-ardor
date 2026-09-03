package com.projectardor.profile.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.profile.domain.UserProfile;
import com.projectardor.profile.repository.UserProfileRepository;

@Service
public class ProfileService {

    private final UserProfileRepository profileRepository;

    public ProfileService(UserProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public UserProfile get(UUID userId) {
        return profileRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用户资料不存在"));
    }

    @Transactional
    public UserProfile update(UUID userId, String displayName, String headline, List<String> targetRoles, String timezone) {
        UserProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用户资料不存在"));
        profile.update(
                normalizeNullable(displayName),
                normalizeNullable(headline),
                normalizeRoles(targetRoles),
                normalizeTimezone(timezone));
        return profile;
    }

    private String normalizeTimezone(String value) {
        String timezone = value == null || value.isBlank() ? "Asia/Shanghai" : value.strip();
        try {
            return ZoneId.of(timezone).getId();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("时区必须是有效的 IANA 时区，例如 Asia/Shanghai");
        }
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<String> normalizeRoles(List<String> roles) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        roles.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(role -> !role.isBlank())
                .forEach(normalized::add);
        return List.copyOf(normalized);
    }
}

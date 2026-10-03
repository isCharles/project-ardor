package com.projectardor.admin.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.domain.UserStatus;
import com.projectardor.usage.MembershipTier;

public record AdminUserResponse(UUID id, String email, UserStatus status, UserRole role,
        MembershipTier membership, Instant createdAt) {
    public static AdminUserResponse from(UserAccount user, MembershipTier membership) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getStatus(), user.getRole(),
                membership, user.getCreatedAt());
    }
}

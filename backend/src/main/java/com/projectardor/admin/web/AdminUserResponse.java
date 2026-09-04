package com.projectardor.admin.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.domain.UserStatus;

public record AdminUserResponse(UUID id, String email, UserStatus status, UserRole role, Instant createdAt) {
    public static AdminUserResponse from(UserAccount user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getStatus(), user.getRole(), user.getCreatedAt());
    }
}

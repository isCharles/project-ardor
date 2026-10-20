package com.projectardor.platform.identity.auth.web;

import java.util.UUID;

import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

public record CurrentUserResponse(UUID id, String email, boolean admin) {

    public static CurrentUserResponse from(UserAccount user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getRole() == com.projectardor.platform.identity.auth.domain.UserRole.ADMIN);
    }

    public static CurrentUserResponse from(ArdorPrincipal principal) {
        return new CurrentUserResponse(principal.userId(), principal.email(), principal.role() == com.projectardor.platform.identity.auth.domain.UserRole.ADMIN);
    }
}

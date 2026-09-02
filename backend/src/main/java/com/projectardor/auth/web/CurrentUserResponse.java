package com.projectardor.auth.web;

import java.util.UUID;

import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.security.ArdorPrincipal;

public record CurrentUserResponse(UUID id, String email) {

    public static CurrentUserResponse from(UserAccount user) {
        return new CurrentUserResponse(user.getId(), user.getEmail());
    }

    public static CurrentUserResponse from(ArdorPrincipal principal) {
        return new CurrentUserResponse(principal.userId(), principal.email());
    }
}

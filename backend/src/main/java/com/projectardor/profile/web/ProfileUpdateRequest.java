package com.projectardor.profile.web;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @Size(max = 120) String displayName,
        @Size(max = 240) String headline,
        @Size(max = 20) List<@NotBlank @Size(max = 120) String> targetRoles) {

    public ProfileUpdateRequest {
        targetRoles = targetRoles == null ? List.of() : targetRoles;
    }
}


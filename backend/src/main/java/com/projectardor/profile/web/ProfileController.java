package com.projectardor.profile.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.profile.service.ProfileService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    ProfileResponse get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return ProfileResponse.from(profileService.get(principal.userId()));
    }

    @PutMapping
    ProfileResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ProfileResponse.from(profileService.update(
                principal.userId(),
                request.displayName(),
                request.headline(),
                request.targetRoles()));
    }
}


package com.projectardor.platform.identity.profile.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.identity.profile.service.ProfileService;

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
        return ProfileResponse.from(profileService.view(principal.userId()));
    }

    @PutMapping
    ProfileResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ProfileResponse.from(profileService.updateView(
                principal.userId(),
                request.displayName(),
                request.headline(),
                request.targetRoles(),
                request.timezone()));
    }
}

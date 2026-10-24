package com.projectardor.platform.identity.profile.web;

import java.util.List;

import com.projectardor.platform.identity.profile.service.ProfileView;

public record ProfileResponse(String displayName, String headline, List<String> targetRoles, String timezone) {

    public static ProfileResponse from(ProfileView profile) {
        return new ProfileResponse(
                profile.displayName(),
                profile.headline(),
                profile.targetRoles(), profile.timezone());
    }
}

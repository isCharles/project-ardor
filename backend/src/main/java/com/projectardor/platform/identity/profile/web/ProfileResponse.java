package com.projectardor.platform.identity.profile.web;

import java.util.List;

import com.projectardor.platform.identity.profile.domain.UserProfile;

public record ProfileResponse(String displayName, String headline, List<String> targetRoles, String timezone) {

    public static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(
                profile.getDisplayName(),
                profile.getHeadline(),
                profile.getTargetRoles(), profile.getTimezone());
    }
}

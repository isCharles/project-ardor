package com.projectardor.platform.identity.profile.service;

import java.util.List;
import com.projectardor.platform.identity.profile.domain.UserProfile;

/** Detached public profile fields; identity, role, preferences and persistence remain private. */
public record ProfileView(String displayName, String headline, List<String> targetRoles, String timezone) {
    public ProfileView { targetRoles = List.copyOf(targetRoles); }
    public static ProfileView from(UserProfile profile) {
        return new ProfileView(profile.getDisplayName(), profile.getHeadline(), profile.getTargetRoles(), profile.getTimezone());
    }
}

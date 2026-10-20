package com.projectardor.admin.web;

import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.domain.UserStatus;

public record AdminUserUpdateRequest(UserStatus status, UserRole role) {
}

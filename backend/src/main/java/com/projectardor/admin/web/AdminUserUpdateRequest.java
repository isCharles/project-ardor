package com.projectardor.admin.web;

import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.domain.UserStatus;

public record AdminUserUpdateRequest(UserStatus status, UserRole role) {
}

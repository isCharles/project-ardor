package com.projectardor.admin.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.admin.web.AdminUserResponse;
import com.projectardor.admin.web.AdminUserUpdateRequest;
import com.projectardor.auth.domain.UserAccount;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.domain.UserStatus;
import com.projectardor.auth.repository.UserAccountRepository;
import com.projectardor.common.web.ResourceNotFoundException;

@Service
public class AdminUserService {
    private final UserAccountRepository repository;

    public AdminUserService(UserAccountRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(AdminUserResponse::from).toList();
    }

    @Transactional
    public AdminUserResponse update(UUID actingAdminId, UUID userId, AdminUserUpdateRequest request) {
        UserAccount user = repository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
        UserStatus nextStatus = request.status() == null ? user.getStatus() : request.status();
        UserRole nextRole = request.role() == null ? user.getRole() : request.role();
        if (actingAdminId.equals(userId) && (nextStatus != UserStatus.ACTIVE || nextRole != UserRole.ADMIN)) {
            throw new IllegalArgumentException("不能禁用或撤销当前登录的管理员");
        }
        if (user.getRole() == UserRole.ADMIN && nextRole != UserRole.ADMIN
                && repository.countByRole(UserRole.ADMIN) <= 1) {
            throw new IllegalArgumentException("系统必须至少保留一名管理员");
        }
        user.setStatus(nextStatus);
        user.setRole(nextRole);
        return AdminUserResponse.from(user);
    }
}

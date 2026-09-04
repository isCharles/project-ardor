package com.projectardor.admin.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.admin.domain.SystemApiConfig;
import com.projectardor.admin.domain.SystemApiServiceType;

public interface SystemApiConfigRepository extends JpaRepository<SystemApiConfig, SystemApiServiceType> {
}

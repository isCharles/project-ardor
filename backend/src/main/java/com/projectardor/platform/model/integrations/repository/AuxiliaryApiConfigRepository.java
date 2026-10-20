package com.projectardor.platform.model.integrations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.platform.model.integrations.domain.AuxiliaryApiConfig;
import com.projectardor.platform.model.integrations.domain.AuxiliaryServiceType;

public interface AuxiliaryApiConfigRepository extends JpaRepository<AuxiliaryApiConfig, UUID> {
    List<AuxiliaryApiConfig> findAllByUserId(UUID userId);
    Optional<AuxiliaryApiConfig> findByUserIdAndServiceType(UUID userId, AuxiliaryServiceType serviceType);
    void deleteByUserIdAndServiceType(UUID userId, AuxiliaryServiceType serviceType);
}

package com.projectardor.integrations.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.integrations.domain.AuxiliaryApiConfig;
import com.projectardor.integrations.domain.AuxiliaryServiceType;

public interface AuxiliaryApiConfigRepository extends JpaRepository<AuxiliaryApiConfig, UUID> {
    List<AuxiliaryApiConfig> findAllByUserId(UUID userId);
    Optional<AuxiliaryApiConfig> findByUserIdAndServiceType(UUID userId, AuxiliaryServiceType serviceType);
    void deleteByUserIdAndServiceType(UUID userId, AuxiliaryServiceType serviceType);
}

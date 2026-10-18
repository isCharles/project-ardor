package com.projectardor.opportunity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.opportunity.domain.JobOpportunity;

public interface JobOpportunityRepository extends JpaRepository<JobOpportunity, UUID> {
    Optional<JobOpportunity> findByIdAndUserId(UUID id, UUID userId);
    List<JobOpportunity> findAllByUserIdOrderByUpdatedAtDesc(UUID userId);
}

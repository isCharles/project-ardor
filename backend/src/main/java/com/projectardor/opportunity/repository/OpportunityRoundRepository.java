package com.projectardor.opportunity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.projectardor.opportunity.domain.OpportunityRound;

public interface OpportunityRoundRepository extends JpaRepository<OpportunityRound, UUID> {
    Optional<OpportunityRound> findByIdAndUserId(UUID id, UUID userId);

    List<OpportunityRound> findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(UUID userId, UUID opportunityId);

    List<OpportunityRound> findAllByUserIdAndOpportunityIdInOrderByRoundNumberAsc(
            UUID userId, Collection<UUID> opportunityIds);

    boolean existsByUserIdAndOpportunityIdAndRoundNumber(UUID userId, UUID opportunityId, int roundNumber);
}

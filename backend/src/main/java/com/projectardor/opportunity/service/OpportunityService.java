package com.projectardor.opportunity.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.domain.OpportunityRound;
import com.projectardor.opportunity.domain.OpportunityStatus;
import com.projectardor.opportunity.domain.RoundStage;
import com.projectardor.opportunity.repository.JobOpportunityRepository;
import com.projectardor.opportunity.repository.OpportunityRoundRepository;
import com.projectardor.opportunity.web.OpportunityCreateRequest;
import com.projectardor.opportunity.web.OpportunityResponse;
import com.projectardor.opportunity.web.OpportunityRoundRequest;
import com.projectardor.opportunity.web.OpportunityUpdateRequest;

/**
 * The only write entry point for interview processes. Every lookup is scoped to
 * the authenticated user; ownership is never read from the request.
 */
@Service
public class OpportunityService {
    private final JobOpportunityRepository opportunities;
    private final OpportunityRoundRepository rounds;

    public OpportunityService(JobOpportunityRepository opportunities, OpportunityRoundRepository rounds) {
        this.opportunities = opportunities;
        this.rounds = rounds;
    }

    @Transactional(readOnly = true)
    public List<OpportunityResponse> list(UUID userId) {
        List<JobOpportunity> owned = opportunities.findAllByUserIdOrderByUpdatedAtDesc(userId);
        if (owned.isEmpty()) return List.of();
        Collection<UUID> ids = owned.stream().map(JobOpportunity::getId).toList();
        Map<UUID, List<OpportunityRound>> byOpportunity = rounds
                .findAllByUserIdAndOpportunityIdInOrderByRoundNumberAsc(userId, ids)
                .stream()
                .collect(Collectors.groupingBy(OpportunityRound::getOpportunityId));
        return owned.stream()
                .map(item -> OpportunityResponse.from(item, byOpportunity.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public OpportunityResponse detail(UUID userId, UUID opportunityId) {
        return response(userId, require(userId, opportunityId));
    }

    @Transactional
    public OpportunityResponse create(UUID userId, OpportunityCreateRequest request) {
        JobOpportunity opportunity = opportunities.save(JobOpportunity.create(
                userId,
                request.company(),
                blankToNull(request.targetRole()),
                blankToNull(request.platform()),
                blankToNull(request.jdContent()),
                blankToNull(request.inviteContent())));

        RoundStage stage = parseStage(request.stage());
        if (stage != null || request.scheduledAt() != null) {
            rounds.save(OpportunityRound.create(userId, opportunity.getId(), 1,
                    stage == null ? RoundStage.OTHER : stage,
                    request.scheduledAt(), null, request.durationMinutes(), blankToNull(request.note())));
        }
        return response(userId, opportunity);
    }

    @Transactional
    public OpportunityResponse update(UUID userId, UUID opportunityId, OpportunityUpdateRequest request) {
        JobOpportunity opportunity = require(userId, opportunityId);
        opportunity.updateDetails(
                request.company(),
                request.targetRole(),
                request.platform(),
                request.jdContent(),
                request.inviteContent());
        OpportunityStatus target = parseStatus(request.status());
        if (target != null) opportunity.transitionTo(target);
        opportunities.save(opportunity);
        return response(userId, opportunity);
    }

    @Transactional
    public OpportunityResponse addRound(UUID userId, UUID opportunityId, OpportunityRoundRequest request) {
        JobOpportunity opportunity = require(userId, opportunityId);
        List<OpportunityRound> existing =
                rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(userId, opportunityId);
        int roundNumber = request.roundNumber() != null
                ? request.roundNumber()
                : existing.stream().mapToInt(OpportunityRound::getRoundNumber).max().orElse(0) + 1;
        if (roundNumber < 1) throw new IllegalArgumentException("轮次必须从 1 开始");
        if (existing.stream().anyMatch(item -> item.getRoundNumber() == roundNumber)) {
            throw new IllegalArgumentException("第 " + roundNumber + " 轮已存在；要改这一轮请用更新轮次，新增时传 0 或省略轮次号");
        }
        RoundStage stage = parseStage(request.stage());
        rounds.save(OpportunityRound.create(userId, opportunityId, roundNumber,
                stage == null ? RoundStage.OTHER : stage,
                request.scheduledAt(), request.occurredAt(), request.durationMinutes(),
                blankToNull(request.note())));
        // Booking the next round moves "next round" back to "scheduled".
        if (request.scheduledAt() != null && opportunity.getStatus() == OpportunityStatus.NEXT_ROUND) {
            opportunity.transitionTo(OpportunityStatus.SCHEDULED);
            opportunities.save(opportunity);
        }
        return response(userId, opportunity);
    }

    @Transactional
    public OpportunityResponse updateRound(UUID userId, UUID opportunityId, UUID roundId,
            OpportunityRoundRequest request) {
        require(userId, opportunityId);
        OpportunityRound round = rounds.findByIdAndUserId(roundId, userId)
                .filter(item -> item.getOpportunityId().equals(opportunityId))
                .orElseThrow(() -> new ResourceNotFoundException("面试轮次不存在"));
        if (request.roundNumber() != null && request.roundNumber() != round.getRoundNumber()) {
            throw new IllegalArgumentException("轮次编号不能通过修改变更，请删除后重建");
        }
        round.update(parseStage(request.stage()), request.scheduledAt(), request.occurredAt(),
                request.durationMinutes(), blankToNull(request.note()));
        rounds.save(round);
        return detail(userId, opportunityId);
    }

    @Transactional
    public OpportunityResponse deleteRound(UUID userId, UUID opportunityId, UUID roundId) {
        require(userId, opportunityId);
        OpportunityRound round = rounds.findByIdAndUserId(roundId, userId)
                .filter(item -> item.getOpportunityId().equals(opportunityId))
                .orElseThrow(() -> new ResourceNotFoundException("面试轮次不存在"));
        rounds.delete(round);
        return detail(userId, opportunityId);
    }

    @Transactional
    public void delete(UUID userId, UUID opportunityId) {
        opportunities.delete(require(userId, opportunityId));
    }

    private JobOpportunity require(UUID userId, UUID opportunityId) {
        return opportunities.findByIdAndUserId(opportunityId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面试流程不存在"));
    }

    private OpportunityResponse response(UUID userId, JobOpportunity opportunity) {
        return OpportunityResponse.from(opportunity,
                rounds.findAllByUserIdAndOpportunityIdOrderByRoundNumberAsc(userId, opportunity.getId()));
    }

    private static OpportunityStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OpportunityStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的面试状态：" + raw.trim());
        }
    }

    private static RoundStage parseStage(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return RoundStage.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的面试环节：" + raw.trim());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

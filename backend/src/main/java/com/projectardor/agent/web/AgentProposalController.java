package com.projectardor.agent.web;

import java.util.Set;
import java.util.UUID;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.agent.service.AgentExecutionAuditStore;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.common.web.ResourceNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Records presentation decisions only. This endpoint cannot execute or authorize a business operation.
 *
 * <p>The legacy {@code messages.proposal_resolutions} projection keeps its first-decision-wins
 * semantics, so old clients keep working. New proposals also get a structured lifecycle row in
 * {@code agent_proposals}; a CONFIRMED row is the user's decision report, not proof that the
 * business mutation succeeded.
 */
@RestController
@RequestMapping("/api/agent/messages")
public class AgentProposalController {
    private final ConversationMessageRepository messages;
    private final ObjectMapper mapper;
    private final AgentExecutionAuditStore executionAudit;

    /** Compatibility constructor for isolated controller tests without the audit store. */
    public AgentProposalController(ConversationMessageRepository messages, ObjectMapper mapper) {
        this(messages, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentProposalController(ConversationMessageRepository messages, ObjectMapper mapper,
            AgentExecutionAuditStore executionAudit) {
        this.messages = messages;
        this.mapper = mapper;
        this.executionAudit = executionAudit;
    }

    public record Decision(String key, String resolution) {}

    private record MatchedProposal(String legacyKey, UUID proposalId) {}

    @PutMapping("/{id}/proposal-resolution")
    @Transactional
    public void resolve(@AuthenticationPrincipal ArdorPrincipal principal, @PathVariable UUID id,
            @RequestBody Decision decision) {
        var message = messages.findByIdAndUserId(id, principal.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (decision.key() == null || decision.key().length() > 4096
                || !Set.of("DONE", "DISMISSED", "EXPIRED", "ACCEPTED").contains(decision.resolution() == null ? "" : decision.resolution())) {
            throw new IllegalArgumentException("Invalid proposal resolution");
        }
        MatchedProposal proposal = findProposal(message, decision.key());
        if (proposal == null) throw new IllegalArgumentException("Proposal not found in message");
        int recorded = messages.resolveProposal(principal.userId(), id, proposal.legacyKey(), decision.resolution());
        if (recorded == 1 && executionAudit != null) {
            // Only the first decision may change the structured proposal lifecycle.
            executionAudit.decideProposal(principal.userId(), id, proposal.proposalId(),
                    proposal.legacyKey(), decision.resolution());
        }
    }

    /** Old clients send the legacy card key; new clients may send the stable proposalId. */
    private MatchedProposal findProposal(com.projectardor.agent.domain.ConversationMessage message, String key) {
        if (!"ASSISTANT".equals(message.getRole()) || message.getRunTrace() == null) return null;
        var proposals = mapper.readTree(message.getRunTrace()).path("proposals");
        for (var proposal : proposals) {
            String requestId = proposal.path("learningPlan").path("requestId").asText("");
            String legacyKey = requestId.isEmpty()
                    ? proposal.path("kind").asText() + ":" + proposal.path("endpoint").asText()
                    : requestId;
            String proposalIdText = proposal.path("proposalId").asText("");
            if (key.equals(legacyKey) || (!proposalIdText.isEmpty() && key.equals(proposalIdText))) {
                return new MatchedProposal(legacyKey, proposalIdText.isEmpty() ? null : UUID.fromString(proposalIdText));
            }
        }
        return null;
    }
}

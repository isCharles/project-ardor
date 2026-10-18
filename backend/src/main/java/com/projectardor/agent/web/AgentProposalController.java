package com.projectardor.agent.web;

import java.util.Set;
import java.util.UUID;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.common.web.ResourceNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Records presentation decisions only. This endpoint cannot execute or authorize a business operation. */
@RestController
@RequestMapping("/api/agent/messages")
public class AgentProposalController {
    private final ConversationMessageRepository messages;
    private final ObjectMapper mapper;

    public AgentProposalController(ConversationMessageRepository messages, ObjectMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    public record Decision(String key, String resolution) {}

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
        boolean found = false;
        if ("ASSISTANT".equals(message.getRole()) && message.getRunTrace() != null) {
            var proposals = mapper.readTree(message.getRunTrace()).path("proposals");
            for (var proposal : proposals) {
                String requestId = proposal.path("learningPlan").path("requestId").asText("");
                String key = requestId.isEmpty() ? proposal.path("kind").asText() + ":" + proposal.path("endpoint").asText() : requestId;
                if (decision.key().equals(key)) { found = true; break; }
            }
        }
        if (!found) throw new IllegalArgumentException("Proposal not found in message");
        messages.resolveProposal(principal.userId(), id, decision.key(), decision.resolution());
    }
}

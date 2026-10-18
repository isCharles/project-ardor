package com.projectardor.agent.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.agent.domain.ConversationMessage;
import com.projectardor.agent.repository.ConversationMessageRepository;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.common.web.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

class AgentProposalControllerTests {
    final ConversationMessageRepository messages = mock(ConversationMessageRepository.class);
    final UUID owner = UUID.randomUUID();
    final ArdorPrincipal principal = new ArdorPrincipal(owner, "fixture@ardor.invalid", "unused", true, UserRole.USER);
    final AgentProposalController controller = new AgentProposalController(messages, new ObjectMapper());

    @Test void storesOnlyDecisionsForAnOwnedRealProposal() {
        var message = ConversationMessage.assistant(owner, UUID.randomUUID(), "Suggested operation",
                "{\"proposals\":[{\"kind\":\"memory_item\",\"endpoint\":\"/api/agent/memory/items/fixture\"}]}");
        when(messages.findByIdAndUserId(message.getId(), owner)).thenReturn(Optional.of(message));
        controller.resolve(principal, message.getId(), new AgentProposalController.Decision("memory_item:/api/agent/memory/items/fixture", "DISMISSED"));
        verify(messages).resolveProposal(owner, message.getId(), "memory_item:/api/agent/memory/items/fixture", "DISMISSED");
        assertThatThrownBy(() -> controller.resolve(principal, message.getId(), new AgentProposalController.Decision("invented", "DONE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anotherUsersMessageIsNotAccessible() {
        UUID id = UUID.randomUUID();
        when(messages.findByIdAndUserId(id, owner)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.resolve(principal, id, new AgentProposalController.Decision("fixture", "DONE")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(messages, never()).resolveProposal(any(), any(), any(), any());
    }

    @Test void learningRequestIdsAreMatchedAndUnknownResolutionsRejected() {
        var message = ConversationMessage.assistant(owner, UUID.randomUUID(), "Learn",
                "{\"proposals\":[{\"kind\":\"learning_plan\",\"learningPlan\":{\"requestId\":\"owned-request\"}}]}");
        when(messages.findByIdAndUserId(message.getId(), owner)).thenReturn(Optional.of(message));
        controller.resolve(principal, message.getId(), new AgentProposalController.Decision("owned-request", "ACCEPTED"));
        verify(messages).resolveProposal(owner, message.getId(), "owned-request", "ACCEPTED");
        assertThatThrownBy(() -> controller.resolve(principal, message.getId(), new AgentProposalController.Decision("owned-request", "ALLOW")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

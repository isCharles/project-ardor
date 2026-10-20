package com.projectardor.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.agent.domain.ConversationMessage;

/** Old responses keep their fields; a stored run link is now visible instead of being dropped. */
class AgentMessageResponseTests {

    @Test
    void exposesTheDurableRequestAndRunLink() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        ConversationMessage message = ConversationMessage.assistant(user, conversation, "好的", "{}");
        message.linkRun(run, run);

        AgentMessageResponse response = AgentMessageResponse.from(message);

        assertThat(response.requestId()).isEqualTo(run);
        assertThat(response.runId()).isEqualTo(run);
        assertThat(response.runTrace()).isEqualTo("{}");
    }

    @Test
    void legacyMessagesWithoutADurableLinkKeepNullInsteadOfGuessing() {
        AgentMessageResponse response = AgentMessageResponse.from(
                ConversationMessage.user(UUID.randomUUID(), UUID.randomUUID(), "历史消息", java.util.List.of()));

        assertThat(response.requestId()).isNull();
        assertThat(response.runId()).isNull();
    }
}

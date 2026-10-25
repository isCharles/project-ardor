package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.AgentContextReference;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.memory.service.AgentContextCompactor;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService;
import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.identity.profile.service.ProfileView;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;

class AgentContextBuilderTests {
    @Test void memorySummaryReferencesAndInterruptedInstructionsKeepTheirRoles() {
        var f = new Fixture();
        var reference = new AgentContextReference("LEARNING", UUID.randomUUID(), "Owned course");
        when(f.tools.contextHint(f.owner, reference.type(), reference.id())).thenReturn("OWNED REFERENCE");
        var stopped = new AgentContextBuilder.HistoryTurn("USER", "Original task before steering", List.of(reference), Instant.now());
        var result = f.builder.prepare(f.owner, f.conversation, "Current task", "Current task", f.config,
                ReasoningDepth.DEEP, null, null, List.of(stopped));
        assertThat(result.systemPrompt()).contains("STABLE MEMORY", "Asia/Shanghai", ReasoningDepth.DEEP.guidance())
                .doesNotContain("SUMMARY-DATA", "OWNED REFERENCE", "Original task before steering");
        assertThat(result.memory().messages().toString()).contains("SUMMARY-DATA", "Earlier user", "Original task before steering", "OWNED REFERENCE");
        verify(f.embedding).context(f.owner, "Current task");
        assertThat(result.attributes()).containsEntry("interruptedMessages", 1).containsEntry("summaryVersion", 2);
        assertThatThrownBy(() -> result.attributes().put("scope", "write")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void inaccessibleHistoryIsExplicitWhileCurrentReferenceStillRequiresOwnership() {
        var f = new Fixture();
        var reference = new AgentContextReference("LEARNING", UUID.randomUUID(), "Unavailable course");
        when(f.tools.contextHint(f.owner, reference.type(), reference.id())).thenThrow(new ResourceNotFoundException("not owned"));
        assertThatThrownBy(() -> f.builder.contextualMessage(f.owner, "Read this", List.of(reference)))
                .isInstanceOf(ResourceNotFoundException.class);
        var result = f.builder.prepare(f.owner, f.conversation, "Continue", "Continue", f.config, ReasoningDepth.AUTO, null, null,
                List.of(new AgentContextBuilder.HistoryTurn("USER", "Old task", List.of(reference), Instant.now())));
        assertThat(result.memory().messages().toString()).contains("此前引用的资料已不可用", "不得假装已读取内容");
        verify(f.tools, times(2)).contextHint(f.owner, reference.type(), reference.id());
        verifyNoMoreInteractions(f.tools);
    }

    @Test void pendingInputAndRecoveredInstructionsAreIncludedInBudgetWithoutSilentEviction() {
        var f = new Fixture();
        when(f.compactor.budgetTokens()).thenReturn(1000);
        String input = "REFERENCE-DATA ".repeat(200);
        String interrupted = "UNFINISHED USER TASK ".repeat(200);
        var result = f.builder.prepare(f.owner, f.conversation, "Current task", input, f.config, ReasoningDepth.AUTO, null, null,
                List.of(new AgentContextBuilder.HistoryTurn("USER", interrupted, List.of(), Instant.now())));
        var pending = ArgumentCaptor.forClass(String.class);
        verify(f.compactor).prepare(eq(f.owner), eq(f.conversation), anyString(), eq(f.config), eq(ReasoningDepth.AUTO), anyList(), any(), pending.capture());
        assertThat(pending.getValue()).contains(input, interrupted);
        assertThat(result.attributes()).containsEntry("contextBudgetTokens", 1000).containsEntry("overContextBudget", true);
        assertThat((Long) result.attributes().get("assembledEstimatedTokens")).isGreaterThan(1000L);
        assertThat(result.memory().messages().toString()).contains(interrupted, "Earlier user");
    }

    private static final class Fixture {
        final UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID();
        final MemoryEmbeddingService embedding = mock(MemoryEmbeddingService.class);
        final AgentContextCompactor compactor = mock(AgentContextCompactor.class);
        final ProfileService profiles = mock(ProfileService.class);
        final CareerAgentTools tools = mock(CareerAgentTools.class);
        final LlmConfigService.LlmRuntimeConfig config = new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.ANTHROPIC_COMPATIBLE, "https://example.invalid", "synthetic-model", "synthetic-only");
        final AgentContextBuilder builder = new AgentContextBuilder(embedding, compactor, profiles, tools);
        Fixture() {
            when(embedding.context(eq(owner), anyString())).thenReturn("STABLE MEMORY");
            when(profiles.view(owner)).thenReturn(new ProfileView("Synthetic owner", "", List.of(), "Asia/Shanghai"));
            when(compactor.budgetTokens()).thenReturn(12000);
            when(compactor.prepare(eq(owner), eq(conversation), anyString(), eq(config), any(), anyList(), any(), anyString())).thenAnswer(call ->
                    new AgentContextCompactor.Prepared(call.getArgument(2), List.of(ConversationMessage.user(owner, conversation, "Earlier user")),
                            2, 4, 10, new CharacterEstimator(), "SUMMARY-DATA"));
        }
    }
    private static final class CharacterEstimator implements TokenCountEstimator {
        public int estimateTokenCountInText(String text) { return text == null ? 0 : text.length(); }
        public int estimateTokenCountInMessage(ChatMessage message) { return message.toString().length(); }
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) { int total=0; for(var item:messages) total+=estimateTokenCountInMessage(item); return total; }
    }
}

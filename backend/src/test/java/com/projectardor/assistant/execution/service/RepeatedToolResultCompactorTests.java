package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

class RepeatedToolResultCompactorTests {
    private static final String RESULT = "synthetic observation ".repeat(180);
    private final AgentContextBudgetPolicy.Budget budget = new AgentContextBudgetPolicy.Budget(10000L, 500, 5000L, 500, 500);

    @Test void duplicateReadResultsShrinkOnlyInRequestCopyAndKeepFirstLastAndAllCallPairs() {
        List<ChatMessage> messages = repeated("list_learning_plans", "{}", RESULT, 4);
        var request = ChatRequest.builder().messages(messages).build();
        var compacted = RepeatedToolResultCompactor.compact(request, budget, null);
        assertThat(compacted).isNotSameAs(request);
        assertThat(compacted.messages()).hasSameSizeAs(messages);
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage before) {
                var after = (ToolExecutionResultMessage) compacted.messages().get(i);
                assertThat(after.id()).isEqualTo(before.id());
                assertThat(after.toolName()).isEqualTo(before.toolName());
                assertThat(after.attributes()).isEqualTo(before.attributes());
                if (before.id().equals("call-0") || before.id().equals("call-3")) assertThat(after.text()).isEqualTo(RESULT);
                else {
                    assertThat(after.text()).contains("call-0");
                    assertThat(after.text().length()).isLessThan(RESULT.length());
                }
                assertThat(before.text()).isEqualTo(RESULT);
            } else assertThat(compacted.messages().get(i)).isSameAs(messages.get(i));
        }
        assertThat(AgentRequestTokens.estimate(compacted, null)).isLessThan(AgentRequestTokens.estimate(request, null));
        assertThat(compacted.messages().getLast()).isEqualTo(UserMessage.from("recent correction"));
    }

    @Test void noChangesForDifferentArgumentsWritesErrorsOrInsufficientRepetition() {
        var writes = ChatRequest.builder().messages(repeated("create_learning_plan", "{}", RESULT, 3)).build();
        assertThat(RepeatedToolResultCompactor.compact(writes, budget, null)).isSameAs(writes);
        var onlyTwo = ChatRequest.builder().messages(repeated("list_learning_plans", "{}", RESULT, 2)).build();
        assertThat(RepeatedToolResultCompactor.compact(onlyTwo, budget, null)).isSameAs(onlyTwo);
        List<ChatMessage> different = repeated("list_learning_plans", "{}", RESULT, 3);
        different.set(2, request("call-1", "list_learning_plans", "{\"page\":2}"));
        var varied = ChatRequest.builder().messages(different).build();
        assertThat(RepeatedToolResultCompactor.compact(varied, budget, null)).isSameAs(varied);
        List<ChatMessage> errors = repeated("list_learning_plans", "{}", RESULT, 3);
        errors.set(3, ToolExecutionResultMessage.builder().id("call-1").toolName("list_learning_plans").text(RESULT).isError(true).build());
        var errorRequest = ChatRequest.builder().messages(errors).build();
        assertThat(RepeatedToolResultCompactor.compact(errorRequest, budget, null)).isSameAs(errorRequest);
        List<ChatMessage> ambiguous = repeated("list_learning_plans", "{}", RESULT, 3);
        ambiguous.set(4, request("call-1", "list_learning_plans", "{}"));
        var ambiguousRequest = ChatRequest.builder().messages(ambiguous).build();
        assertThat(RepeatedToolResultCompactor.compact(ambiguousRequest, budget, null)).isSameAs(ambiguousRequest);
        assertThat(RepeatedToolResultCompactor.compact(errorRequest, new AgentContextBudgetPolicy.Budget(null, 100000, null, 0, 0), null)).isSameAs(errorRequest);
    }

    @Test void transformedRequestReachesListenersAndHardLimitFenceBeforeTransport() {
        var delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(dev.langchain4j.model.chat.request.ChatRequestParameters.builder().build());
        var guarded = ContextBudgetedModels.streaming(delegate, budget, null);
        var wrapped = RepeatedToolResultCompactor.streaming(guarded, budget, null);
        var handler = mock(StreamingChatResponseHandler.class);
        var request = ChatRequest.builder().messages(repeated("list_learning_plans", "{}", RESULT, 4)).build();
        assertThat(AgentRequestTokens.estimate(request, null)).isGreaterThan(budget.hardInputTokens());
        wrapped.chat(request, handler);
        var captured = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(delegate).doChat(captured.capture(), any());
        assertThat(AgentRequestTokens.estimate(captured.getValue(), null)).isLessThanOrEqualTo(budget.hardInputTokens());
        verify(handler, never()).onError(any());
    }

    private static List<ChatMessage> repeated(String tool, String args, String output, int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "call-" + i;
            messages.add(request(id, tool, args));
            messages.add(ToolExecutionResultMessage.builder().id(id).toolName(tool).text(output).attributes(Map.of("source", "fixture")).build());
        }
        messages.add(UserMessage.from("recent correction"));
        return messages;
    }
    private static AiMessage request(String id, String tool, String args) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(tool).arguments(args).build());
    }
}

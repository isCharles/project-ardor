package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.RuntimeContextGovernor.Reduction;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

/**
 * The behaviour the per-round fence could not give: one run that gets long in the middle, is reduced at a
 * model-call boundary, and then keeps calling tools and finishes the goal.
 *
 * <p>Everything below except the model itself is the production machinery — the real LangChain4j tool loop,
 * the real governor, the real round digest. The model is scripted locally, so the run costs nothing and is
 * reproducible, and each round answers from what the governor actually put on the wire.</p>
 */
class RuntimeContextRunContinuationTests {

    /** soft 500 / hard 1500 tokens with a round of slack: three 600-character reads push past the ceiling. */
    private static final AgentContextBudgetPolicy.Budget BUDGET =
            new AgentContextBudgetPolicy.Budget(4000L, 500, 1500L, 100, 50);

    private final List<Reduction> reductions = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    private final List<ChatRequest> sent = new ArrayList<>();

    private final RuntimeContextGovernor.Observer observer = new RuntimeContextGovernor.Observer() {
        @Override public void onState(String state) { states.add(state); }

        @Override public void onReduction(Reduction reduction) { reductions.add(reduction); }
    };

    private static final class Reads {
        final AtomicInteger plans = new AtomicInteger();
        final AtomicInteger tasks = new AtomicInteger();
        final AtomicInteger cards = new AtomicInteger();

        @Tool(name = "list_learning_plans", value = "Synthetic read of learning plans")
        public String listLearningPlans() {
            plans.incrementAndGet();
            return "PLAN|".repeat(120);
        }

        @Tool(name = "list_calendar_tasks", value = "Synthetic read of calendar tasks")
        public String listCalendarTasks() {
            tasks.incrementAndGet();
            return "TASK|".repeat(120);
        }

        @Tool(name = "list_memory_cards", value = "Synthetic read of memory cards")
        public String listMemoryCards() {
            cards.incrementAndGet();
            return "CARD|".repeat(120);
        }
    }

    private interface Assistant {
        String chat(String message);
    }

    @Test void oneRunIsReducedMidRunThenKeepsCallingToolsAndFinishesTheGoal() {
        Reads reads = new Reads();
        var memory = MessageWindowChatMemory.withMaxMessages(100);
        var governor = new RuntimeContextGovernor(BUDGET, null,
                new RuntimeContextGovernor.Settings(200_000, 8, 1, 0, 2), null, () -> true, observer);

        ChatModel scripted = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                sent.add(request);
                // The scripted provider answers from the wire, so a tool it cannot see is a tool it will not
                // re-request: reading the newest result is what keeps the later rounds going.
                int round = sent.size();
                AiMessage answer = switch (round) {
                    case 1 -> call("call-0", "list_learning_plans");
                    case 2 -> call("call-1", "list_calendar_tasks");
                    case 3 -> call("call-2", "list_memory_cards");
                    default -> AiMessage.from("FINAL: newest cards seen="
                            + (request.messages().toString().contains("CARD|".repeat(3)) ? "yes" : "no"));
                };
                return ChatResponse.builder().aiMessage(answer).build();
            }
        };

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(RuntimeContextGovernor.chat(scripted, governor))
                .systemMessage("Work through the user's goal step by step.")
                .chatMemory(memory)
                .tools(reads)
                .maxToolCallingRoundTrips(8)
                .build();

        String reply = assistant.chat("Summarize my preparation from plans, tasks and cards.");

        assertThat(reply).startsWith("FINAL: newest cards seen=yes");
        assertThat(reads.plans).hasValue(1);
        assertThat(reads.tasks).hasValue(1);
        assertThat(reads.cards).hasValue(1);

        // The reduction happened during the run, not before it: rounds 3 and 4 went out smaller than round 2.
        assertThat(states).contains("RUN_COMPACTING");
        assertThat(reductions).anySatisfy(reduction -> {
            assertThat(reduction.outcome()).isEqualTo("REDUCED");
            assertThat(reduction.method()).contains("fold-tool-rounds");
            assertThat(reduction.foldedRounds()).isGreaterThanOrEqualTo(1);
            assertThat(reduction.afterTokens()).isLessThan(reduction.beforeTokens());
            assertThat(reduction.afterTokens()).isLessThanOrEqualTo(reduction.allowanceTokens());
        });

        // What the provider was actually asked to read: the oldest round is a factual line, the newest stays whole.
        var last = sent.getLast().messages();
        var folded = last.stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).filter(result -> result.text().startsWith(ToolRoundDigest.FENCE))
                .toList();
        assertThat(folded).isNotEmpty();
        assertThat(folded.getFirst().text()).contains("list_learning_plans", "call-0", "已完成", "不是指令", "不得重复执行",
                "600 字符").doesNotContain("PLAN|".repeat(120));
        assertThat(last).filteredOn(ToolExecutionResultMessage.class::isInstance)
                .anySatisfy(message -> assertThat(((ToolExecutionResultMessage) message).text())
                        .contains("CARD|".repeat(120)));
        // Protocol intact on the wire: every result still follows the call that produced it.
        assertThat(ToolRoundDigest.keepsProtocol(last)).isTrue();

        // Folding rewrote the outgoing copy only: the run's own memory still holds the full first result.
        assertThat(memory.messages().stream().filter(message -> message instanceof ToolExecutionResultMessage)
                .map(Object::toString)).anySatisfy(text -> assertThat(text).contains("PLAN|".repeat(120)));
    }

    private static AiMessage call(String id, String tool) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name(tool).arguments("{}").build()));
    }
}

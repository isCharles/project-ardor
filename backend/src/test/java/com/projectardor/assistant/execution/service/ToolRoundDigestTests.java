package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

class ToolRoundDigestTests {

    @Test void aCallAndItsResultsScanAsOneRoundThatCanBeFoldedAsOne() {
        var messages = List.<ChatMessage>of(
                UserMessage.from("goal"),
                AiMessage.from(List.of(call("call-1", "list_calendar_tasks"), call("call-2", "list_memory_cards"))),
                ToolExecutionResultMessage.from("call-1", "list_calendar_tasks", "one"),
                ToolExecutionResultMessage.from("call-2", "list_memory_cards", "two"),
                UserMessage.from("next"));

        var scan = ToolRoundDigest.scan(messages);

        assertThat(scan.protocolValid()).isTrue();
        assertThat(scan.groups()).singleElement().satisfies(group -> {
            assertThat(group.start()).isEqualTo(1);
            assertThat(group.end()).isEqualTo(4);
            assertThat(group.pending()).isFalse();
            assertThat(group.writes()).isFalse();
        });
    }

    @Test void aRoundStillWaitingForAResultIsNeverAFoldCandidate() {
        var messages = List.<ChatMessage>of(
                UserMessage.from("goal"),
                AiMessage.from(List.of(call("call-1", "list_calendar_tasks"), call("call-2", "list_memory_cards"))),
                ToolExecutionResultMessage.from("call-1", "list_calendar_tasks", "one"),
                UserMessage.from("next"));

        assertThat(ToolRoundDigest.scan(messages).groups().getFirst().pending()).isTrue();
    }

    @Test void aWriteRoundIsMarkedSoFoldingHasToSayItFinished() {
        var messages = List.<ChatMessage>of(
                UserMessage.from("goal"),
                AiMessage.from(call("call-1", "create_calendar_task")),
                ToolExecutionResultMessage.from("call-1", "create_calendar_task", "{\"id\":\"task-9\"}"),
                UserMessage.from("next"));

        var group = ToolRoundDigest.scan(messages).groups().getFirst();

        assertThat(group.writes()).isTrue();
        assertThat(ToolRoundDigest.line(group, 1)).contains("create_calendar_task", "已完成", "task-9", "不得重复执行");
    }

    @Test void aFailedCallIsRecordedAsFailedRatherThanAsAnEmptySuccess() {
        var result = ToolExecutionResultMessage.builder().id("call-1").toolName("search_web")
                .text("上游返回 503").isError(true).build();
        var messages = List.<ChatMessage>of(
                UserMessage.from("goal"), AiMessage.from(call("call-1", "search_web")), result,
                UserMessage.from("next"));

        assertThat(ToolRoundDigest.line(ToolRoundDigest.scan(messages).groups().getFirst(), 1))
                .contains("失败").contains("上游返回 503");
    }

    @Test void aResultWithoutItsCallIsABrokenPairingThatFoldingMustNotPretendToFix() {
        var messages = new ArrayList<ChatMessage>(List.of(
                UserMessage.from("goal"),
                ToolExecutionResultMessage.from("orphan", "list_calendar_tasks", "where did this come from"),
                UserMessage.from("next")));

        assertThat(ToolRoundDigest.keepsProtocol(messages)).isFalse();
    }

    @Test void twoResultsClaimingTheSameCallIdCannotBeToldApart() {
        var messages = new ArrayList<ChatMessage>(List.of(
                UserMessage.from("goal"),
                AiMessage.from(call("call-1", "list_calendar_tasks")),
                ToolExecutionResultMessage.from("call-1", "list_calendar_tasks", "first"),
                ToolExecutionResultMessage.from("call-1", "list_calendar_tasks", "second"),
                UserMessage.from("next")));

        assertThat(ToolRoundDigest.keepsProtocol(messages)).isFalse();
    }

    @Test void foldingTextNeverLosesTheArgumentsNeededToReadTheSourceAgain() {
        var messages = List.<ChatMessage>of(
                UserMessage.from("goal"),
                AiMessage.from(call("call-1", "search_web", "{\"query\":\"后端实习\",\"n\":10}")),
                ToolExecutionResultMessage.from("call-1", "search_web", "w".repeat(9_000)),
                UserMessage.from("next"));

        String line = ToolRoundDigest.line(ToolRoundDigest.scan(messages).groups().getFirst(), 1);

        assertThat(line).contains("search_web").contains("后端实习").contains("call-1");
        assertThat(line.length()).isLessThan(600);
    }

    private static ToolExecutionRequest call(String id, String name) {
        return call(id, name, "{}");
    }

    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
}

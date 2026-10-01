package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.recap.domain.InterviewRecapQuestion;
import com.projectardor.recap.domain.InterviewReplayAttempt;
import com.projectardor.recap.domain.QuestionPerformance;
import com.projectardor.recap.domain.ReplayVerdict;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;
import com.projectardor.recap.repository.InterviewReplayAttemptRepository;

import tools.jackson.databind.ObjectMapper;

class InterviewReplayServiceTests {
    private final InterviewRecapQuestionRepository questions = mock(InterviewRecapQuestionRepository.class);
    private final InterviewReplayAttemptRepository attempts = mock(InterviewReplayAttemptRepository.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final InterviewRetestService retests = mock(InterviewRetestService.class);
    private final InterviewReplayService service = new InterviewReplayService(questions,
            mock(InterviewRecapRepository.class), attempts, llm, mock(LlmJsonParser.class), retests);

    @Test
    void foreignQuestionNeverReachesModel() {
        UUID userId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        when(questions.findByIdAndUserId(questionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(userId, questionId, UUID.randomUUID(), "我的回答"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void repeatedRequestReturnsSavedAttemptWithoutAnotherModelCall() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID requestId = UUID.randomUUID();
        InterviewReplayAttempt saved = InterviewReplayAttempt.create(userId, source.getId(), requestId,
                "我的回答", ReplayVerdict.UNKNOWN, "材料不足", List.of(), List.of(), null, "model");
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(attempts.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.of(saved));

        assertThat(service.submit(userId, source.getId(), requestId, "我的回答")).isSameAs(saved);
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void newAnswerPersistsAConservativeComparison() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID requestId = UUID.randomUUID();
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(attempts.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.empty());
        when(llm.completeJson(any(), any(), any())).thenReturn(new LlmGateway.LlmResult("""
                {"verdict":"CLEARER","comparison":"这次解释了内存区域，但缺少示例。",
                 "improvements":["说明了堆和栈的区别"],"remainingGaps":["缺少具体示例"],
                 "nextChallenge":"举一个堆分配的例子"}
                """, "test-model"));
        when(attempts.save(any())).thenAnswer(call -> call.getArgument(0));
        InterviewReplayService realParserService = new InterviewReplayService(questions,
                mock(InterviewRecapRepository.class), attempts, llm,
                new LlmJsonParser(new ObjectMapper()), retests);

        InterviewReplayAttempt saved = realParserService.submit(userId, source.getId(), requestId,
                "堆存对象，栈存局部变量和调用帧。概念有区别，但我还没有举例。");

        assertThat(saved.getVerdict()).isEqualTo(ReplayVerdict.CLEARER);
        assertThat(saved.getImprovements()).containsExactly("说明了堆和栈的区别");
        assertThat(saved.getRemainingGaps()).containsExactly("缺少具体示例");
    }

    @Test
    void scheduledRetestUsesVariantQuestionAndCompletesAfterSaving() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String challenge = "结合一次线上故障，解释 JVM 堆内存诊断过程";
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(attempts.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.empty());
        when(retests.challenge(userId, source.getId(), taskId)).thenReturn(challenge);
        when(llm.completeJson(any(), any(), any())).thenReturn(new LlmGateway.LlmResult("""
                {"verdict":"SIMILAR","comparison":"回答仍缺诊断步骤", "improvements":[],
                 "remainingGaps":["缺少诊断步骤"],"nextChallenge":"如何定位泄漏？"}
                """, "test-model"));
        when(attempts.save(any())).thenAnswer(call -> call.getArgument(0));
        InterviewReplayService realParserService = new InterviewReplayService(questions,
                mock(InterviewRecapRepository.class), attempts, llm,
                new LlmJsonParser(new ObjectMapper()), retests);

        InterviewReplayAttempt saved = realParserService.submit(userId, source.getId(), requestId,
                "先看内存曲线，再获取堆转储。", taskId);

        assertThat(saved.getChallengeText()).isEqualTo(challenge);
        assertThat(saved.getRetestTaskId()).isEqualTo(taskId);
        verify(llm).completeJson(any(), any(), argThat(prompt -> prompt.contains(challenge)));
        var sequence = org.mockito.Mockito.inOrder(attempts, retests);
        sequence.verify(attempts).save(any());
        sequence.verify(retests).complete(userId, source.getId(), taskId, challenge);
    }

    @Test
    void rejectedRetestDoesNotCallModel() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID taskId = UUID.randomUUID();
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(retests.challenge(userId, source.getId(), taskId))
                .thenThrow(new ResourceNotFoundException("待复测任务不存在"));

        assertThatThrownBy(() -> service.submit(userId, source.getId(), UUID.randomUUID(), "回答", taskId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void repeatedRetestRequestCompletesWithoutAnotherModelCall() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String challenge = "介绍 JVM 的内存诊断";
        InterviewReplayAttempt saved = InterviewReplayAttempt.create(userId, source.getId(), requestId,
                "回答", ReplayVerdict.UNKNOWN, "待比较", List.of(), List.of(), null, "model", challenge, taskId);
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(attempts.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.of(saved));

        assertThat(service.submit(userId, source.getId(), requestId, "回答", taskId)).isSameAs(saved);
        verify(retests).complete(userId, source.getId(), taskId, challenge);
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void requestIdCannotBeReusedForAnotherRetest() {
        UUID userId = UUID.randomUUID();
        InterviewRecapQuestion source = question(userId);
        UUID originalTask = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        InterviewReplayAttempt saved = InterviewReplayAttempt.create(userId, source.getId(), requestId,
                "回答", ReplayVerdict.UNKNOWN, "待比较", List.of(), List.of(), null, "model",
                "介绍 JVM", originalTask);
        when(questions.findByIdAndUserId(source.getId(), userId)).thenReturn(Optional.of(source));
        when(attempts.findByUserIdAndRequestId(userId, requestId)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.submit(userId, source.getId(), requestId, "回答", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(retests, never()).complete(any(), any(), any(), any());
        verify(llm, never()).completeJson(any(), any(), any());
    }

    @Test
    void missingOriginalAnswerCannotBeCalledImprovement() {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree("""
                {"verdict":"CLEARER","comparison":"明显进步","improvements":["更完整"],
                 "remainingGaps":["还缺例子"],"nextChallenge":"请举例"}
                """);

        var result = InterviewReplayService.parseAssessment(root, false);

        assertThat(result.verdict()).isEqualTo(ReplayVerdict.UNKNOWN);
        assertThat(result.improvements()).isEmpty();
        assertThat(result.comparison()).contains("无法判断是否进步");
    }

    private InterviewRecapQuestion question(UUID userId) {
        return InterviewRecapQuestion.create(userId, UUID.randomUUID(), 1, "解释 JVM", "不知道",
                List.of(), "概念欠缺", QuestionPerformance.WEAK, "没有说明内存区域", null, List.of("JVM"));
    }
}

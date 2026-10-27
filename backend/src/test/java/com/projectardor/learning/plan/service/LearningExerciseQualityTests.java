package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.platform.model.llm.service.LlmGateway;
import tools.jackson.databind.ObjectMapper;

class LearningExerciseQualityTests {
    final ObjectMapper mapper = new ObjectMapper();
    final LlmGateway llm = mock(LlmGateway.class);
    final LearningPlanService service = new LearningPlanService(null, null, llm, new LlmJsonParser(mapper), mapper,
            null, null, null, null, null, null, mock(PlatformTransactionManager.class));
    final UUID owner = UUID.randomUUID();

    String questions(List<String> options, int answer) {
        return mapper.writeValueAsString(List.of(
                Map.of("question", "滑动窗口如何区分同一毫秒的不同请求？", "type", "MULTIPLE_CHOICE",
                        "options", options, "correctOption", answer, "rubric", List.of("member 使用请求 ID，score 使用毫秒时间戳")),
                Map.of("question", "Lua 脚本能原子执行同节点的限流检查与写入。", "type", "TRUE_FALSE",
                        "correctAnswer", true, "rubric", List.of("同节点脚本不与其他命令交错"))));
    }
    String balanced() { return questions(List.of("member 使用请求 ID，score 使用毫秒时间戳", "member 使用固定值，score 使用毫秒时间戳", "member 使用秒时间戳，score 使用固定值"), 0); }
    String obviousCue() { return questions(List.of("会", "不会", "每个请求使用独立请求 ID 作为 member，并以毫秒时间戳作为 score，才能避免同一毫秒的不同请求相互覆盖并统计窗口内的全部调用"), 2); }

    @Test void validQuestionsCostNoAdditionalModelCallAndKeepTheirAnswerKeys() {
        var result = service.generatedExercises(owner, mapper.readTree(balanced()), "Redis", Map.of());
        assertThat(result.getFirst()).containsEntry("correctOption", 0).containsEntry("qualityVersion", LearningExerciseQuality.VERSION);
        assertThat(result.getLast()).containsEntry("correctOption", 0);
        verifyNoInteractions(llm);
    }
    @Test void repairsAnObviousLengthCueOnceWithoutTruncatingTheReturnedAnswer() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult("{\"exercises\":" + balanced() + "}", "synthetic"));
        var result = service.generatedExercises(owner, mapper.readTree(obviousCue()), "Redis", Map.of("summary", "滑动窗口"));
        assertThat(result.getFirst().get("options")).isEqualTo(List.of("member 使用请求 ID，score 使用毫秒时间戳", "member 使用固定值，score 使用毫秒时间戳", "member 使用秒时间戳，score 使用固定值"));
        verify(llm, times(1)).completeJson(eq(owner), contains("不得截短正确答案"), contains("明显长于干扰项"));
    }
    @Test void doesNotLoopOrPersistAnUnrepairedCue() {
        when(llm.completeJson(eq(owner), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult("{\"exercises\":" + obviousCue() + "}", "synthetic"));
        assertThatThrownBy(() -> service.generatedExercises(owner, mapper.readTree(obviousCue()), "Redis", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("修复后仍存在");
        verify(llm, times(1)).completeJson(eq(owner), anyString(), anyString());
    }
    @Test void repairCannotRewriteOtherQuestionTypesOrDropQuestions() {
        String changed = balanced().replace("Lua 脚本能原子执行同节点的限流检查与写入。", "不应被采纳的改题");
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("{\"exercises\":" + changed + "}", "synthetic"));
        var result = service.generatedExercises(owner, mapper.readTree(obviousCue()), "Redis", Map.of());
        assertThat(result.getLast()).containsEntry("question", "Lua 脚本能原子执行同节点的限流检查与写入。");
        when(llm.completeJson(eq(owner), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("{\"exercises\":[]}", "synthetic"));
        assertThatThrownBy(() -> service.generatedExercises(owner, mapper.readTree(obviousCue()), "Redis", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("数量发生变化");
    }
    @Test void canonicalDuplicatesAreRejectedButCodeCaseAndLiteralSpacesRemainDistinct() {
        assertThat(LearningExerciseQuality.issues(mapper.readTree(questions(List.of("café", "cafe\u0301", "other"), 0)))).isNotEmpty();
        assertThat(LearningExerciseQuality.issues(mapper.readTree(questions(List.of("value", "Value", "\"a  b\""), 0)))).isEmpty();
        assertThat(LearningExerciseQuality.issues(mapper.readTree(questions(List.of("\"a b\"", "\"a  b\"", "\"a   b\""), 0)))).isEmpty();
    }
}

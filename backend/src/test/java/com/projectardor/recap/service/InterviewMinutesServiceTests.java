package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.recap.domain.InterviewRecapJob;
import tools.jackson.databind.ObjectMapper;

class InterviewMinutesServiceTests {
    @Test void segmentationPreservesEveryCharacterIncludingSurrogates() {
        String source="[00:01] interviewer\n" + "中".repeat(5999) + "😀\n[10:00] candidate\n" + "x".repeat(14000);
        var chunks=InterviewMinutesService.chunks(source,6000);
        assertThat(String.join("",chunks)).isEqualTo(source);
        assertThat(chunks).allSatisfy(c -> {assertThat(c.length()).isLessThanOrEqualTo(6000);assertThat(Character.isHighSurrogate(c.charAt(c.length()-1))).isFalse();});
    }
    @Test void invalidMinutesAreRejectedRatherThanTruncated() {
        assertThatThrownBy(() -> InterviewMinutesService.validate("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewMinutesService.validate("x".repeat(50001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void draftSkillRunsPerBoundedChunkAndCannotGenerateAReview() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var llm=mock(LlmGateway.class);
        UUID owner=UUID.randomUUID();
        var job=InterviewRecapJob.create(owner,"hash","synthetic transcript ".repeat(600),UUID.randomUUID());
        when(llm.completeJson(eq(owner),anyString(),anyString())).thenReturn(new LlmGateway.LlmResult("{\"minutes\":\"## 主问题\\n面试官问题：测试？\\n候选人回答：待确认，材料不完整。\"}","fixture"));
        var service=new InterviewMinutesService(jdbc,llm,new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class));
        service.prepare(job);
        verify(llm,atLeast(2)).completeJson(eq(owner),contains("只整理事实，不评价"),contains("<transcript>"));
        verify(jdbc).update(contains("INSERT INTO interview_minutes("),eq(job.getId()),eq(owner),contains("待确认"));
        verify(jdbc,never()).update(contains("INSERT INTO interview_minutes_reviews"),any(Object[].class));
    }
    @Test void malformedModelOutputNeverPersistsPartialMinutes() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var llm=mock(LlmGateway.class);
        var job=InterviewRecapJob.create(UUID.randomUUID(),"hash","Synthetic original transcript with unclear speakers and missing answers.",UUID.randomUUID());
        when(llm.completeJson(any(),anyString(),anyString())).thenReturn(new LlmGateway.LlmResult("{\"minutes\":\"\"}","fixture"));
        var service=new InterviewMinutesService(jdbc,llm,new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class));
        assertThatThrownBy(() -> service.prepare(job)).isInstanceOf(IllegalStateException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}

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
import org.springframework.jdbc.core.RowMapper;

class InterviewMinutesServiceTests {
    private static final String MINUTES="Confirmed interviewer question and candidate answer; missing details remain uncertain.";

    @Test void firstConfirmationIsIncludedInTheAlreadyPaidUpload() throws Exception {
        var quota=mock(com.projectardor.usage.QuotaService.class);
        var jdbc=confirmationJdbc(false);
        var service=new InterviewMinutesService(jdbc,mock(LlmGateway.class),new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class),quota);
        service.confirm(UUID.randomUUID(),UUID.randomUUID(),1);
        verifyNoInteractions(quota);
    }

    @Test void subsequentRevisionConsumesAnIdempotentServerBoundCharge() throws Exception {
        var quota=mock(com.projectardor.usage.QuotaService.class);
        var jdbc=confirmationJdbc(true);
        var service=new InterviewMinutesService(jdbc,mock(LlmGateway.class),new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class),quota);
        UUID user=UUID.randomUUID(),draft=UUID.randomUUID();
        service.confirm(user,draft,1);
        verify(quota).consume(eq(user),eq(com.projectardor.usage.UsageFeature.INTERVIEW_RECAP),any(UUID.class));
    }

    private JdbcTemplate confirmationJdbc(boolean previous) {
        var jdbc=mock(JdbcTemplate.class);
        doAnswer(call -> {
            String sql=call.getArgument(0);
            if(sql.startsWith("SELECT revision")) return java.util.Collections.singletonList(new Object[]{1L,MINUTES});
            if(sql.startsWith("SELECT attempts")) return java.util.List.of(0);
            if(sql.contains("j.raw_content")) return java.util.List.of(new InterviewMinutesService.Draft(UUID.randomUUID(),"raw",MINUTES,1,"COMPLETED",null,java.util.List.of()));
            return java.util.List.of();
        }).when(jdbc).query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),any(Object[].class));
        doAnswer(call -> previous && !((String)call.getArgument(0)).contains("AND revision=?"))
                .when(jdbc).queryForObject(anyString(),eq(Boolean.class),any(Object[].class));
        return jdbc;
    }

    @Test void reviewRetryUsesTheSharedQuotaGate() throws Exception {
        var quota=mock(com.projectardor.usage.QuotaService.class);
        var jdbc=confirmationJdbc(false);
        when(jdbc.update(contains("UPDATE interview_minutes_reviews"),any(Object[].class))).thenReturn(1);
        var service=new InterviewMinutesService(jdbc,mock(LlmGateway.class),new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class),quota);
        UUID user=UUID.randomUUID();
        service.retryReview(user,UUID.randomUUID(),UUID.randomUUID());
        verify(quota).consume(eq(user),eq(com.projectardor.usage.UsageFeature.INTERVIEW_RECAP),any(UUID.class));
    }
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
        var service=new InterviewMinutesService(jdbc,llm,new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class),mock(com.projectardor.usage.QuotaService.class));
        service.prepare(job);
        verify(llm,atLeast(2)).completeJson(eq(owner),contains("只整理事实，不评价"),contains("<transcript>"));
        verify(jdbc).update(contains("INSERT INTO interview_minutes("),eq(job.getId()),eq(owner),contains("待确认"));
        verify(jdbc,never()).update(contains("INSERT INTO interview_minutes_reviews"),any(Object[].class));
    }
    @Test void malformedModelOutputNeverPersistsPartialMinutes() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var llm=mock(LlmGateway.class);
        var job=InterviewRecapJob.create(UUID.randomUUID(),"hash","Synthetic original transcript with unclear speakers and missing answers.",UUID.randomUUID());
        when(llm.completeJson(any(),anyString(),anyString())).thenReturn(new LlmGateway.LlmResult("{\"minutes\":\"\"}","fixture"));
        var service=new InterviewMinutesService(jdbc,llm,new LlmJsonParser(new ObjectMapper()),mock(PlatformTransactionManager.class),mock(com.projectardor.usage.QuotaService.class));
        assertThatThrownBy(() -> service.prepare(job)).isInstanceOf(IllegalStateException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}

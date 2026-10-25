package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CareerAgentPromptTests {

    @Test
    void systemPromptStaysSmallAndKeepsCoreInvariants() {
        String prompt = AgentContextBuilder.SYSTEM_PROMPT;

        assertThat(prompt.length()).isLessThan(3_000);
        assertThat(prompt).contains("先读后写", "确认", "不可信", "长期记忆");
        assertThat(prompt).doesNotContain("17a", "23b", "27a", "学习单元", "学校课表");
    }
}

package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApprovedAgentToolsTests {

    @Test
    void acceptsMemoryContentThatRepeatsTheUserWords() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("add_memory",
                "{\"memory\":\"现在研二了\"}", "现在研二了")).isNull();
        assertThat(ApprovedAgentTools.memoryWriteRejection("update_memory",
                "{\"memoryId\":\"abc\",\"content\":\"现在研二了\"}", "现在研二了")).isNull();
    }

    @Test
    void rejectsRewrittenMemoryBeforeAskingForApproval() {
        String rejection = ApprovedAgentTools.memoryWriteRejection("update_memory",
                "{\"memoryId\":\"abc\",\"content\":\"用户目前是研二（研究生二年级）\"}", "现在研二了");
        assertThat(rejection).contains("逐字引用").contains("不要继续换说法");
    }

    @Test
    void skipsThePrecheckWhenTheTurnTextIsUnknown() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("add_memory",
                "{\"memory\":\"改写过的内容\"}", null)).isNull();
    }

    @Test
    void ignoresToolsWithoutMemoryArguments() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("create_calendar_task",
                "{\"title\":\"组会\"}", "帮我加个组会")).isNull();
    }

    @Test
    void readsEscapedStringArguments() {
        assertThat(ApprovedAgentTools.argumentString(
                "{\"memory\" : \"他说\\\"好\\\"\\n下一行\"}", "memory")).isEqualTo("他说\"好\"\n下一行");
        assertThat(ApprovedAgentTools.argumentString("{\"content\":\"现在研二了\"}", "memory")).isNull();
    }
}

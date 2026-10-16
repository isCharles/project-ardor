package com.projectardor.agent.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolIntentPolicyTests {
    @Test void correctionCannotInheritARevokedOrNarrowedWrite() {
        for (String correction : new String[] { "不要创建那个提醒", "Don't create that reminder", "只创建第一个", "保留第二个", "Cancel that" }) {
            assertThat(ToolIntentPolicy.permitsInherited("create_calendar_task", correction, "请创建三个日历提醒")).as(correction).isFalse();
        }
        assertThat(ToolIntentPolicy.permitsInherited("create_calendar_task", "时间用明天", "请创建日历提醒")).isTrue();
    }
    @Test void dateCorrectionDoesNotRequireRepeatingTheModuleName() {
        for (String request : new String[] { "帮我把第二个改到明天", "修改一下日期", "把这个时间改为16号", "改到下周", "Move this to tomorrow", "Reschedule for Friday" }) {
            assertThat(ToolIntentPolicy.permits("update_learning_plan", request)).as(request).isTrue();
            assertThat(ToolIntentPolicy.permits("update_calendar_task", request)).as(request).isTrue();
            assertThat(ToolIntentPolicy.permits("create_calendar_task", request)).as(request).isFalse();
            assertThat(ToolIntentPolicy.permits("update_memory", request)).as(request).isFalse();
        }
        for (String request : new String[] { "不要修改日期", "查询日期", "Explain how to change the date", "请查询\n> 修改日期", "请查询\n```修改日期```" }) {
            assertThat(ToolIntentPolicy.permits("update_learning_plan", request)).as(request).isFalse();
        }
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "修改简历日期")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_calendar_task", "修改学习日期")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "修改学习日期")).isTrue();
    }
    @Test void retrievalAndQuotedInstructionsCannotGrantWriteCapabilities() {
        for (String request : new String[] { "Search for calendar examples", "请查询日历", "Explain how to create a calendar task",
                "请搜索资料\n> 创建日历待办", "请查询\n```create calendar task```" }) {
            assertThat(ToolIntentPolicy.permits("create_calendar_task", request)).as(request).isFalse();
        }
    }
    @Test void directBilingualRequestsPermitOrdinaryWrites() {
        assertThat(ToolIntentPolicy.permits("create_calendar_task", "请帮我添加明天的日程")).isTrue();
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "帮我把学习时间改成明天")).isTrue();
        assertThat(ToolIntentPolicy.permits("create_calendar_task", "Please create a calendar task tomorrow")).isTrue();
        assertThat(ToolIntentPolicy.permits("record_application_count", "今天投了20份")).isTrue();
        assertThat(ToolIntentPolicy.permits("unknown_write", "创建所有东西")).isFalse();
    }
    @Test void actionAndModuleMustReferToTheSameClause() {
        assertThat(ToolIntentPolicy.permits("create_calendar_task", "请整理这篇资料，看看我的日历")).isFalse();
        assertThat(ToolIntentPolicy.permits("cancel_interview", "请创建一次模拟面试")).isFalse();
        assertThat(ToolIntentPolicy.permits("submit_interview_answer", "请创建一次模拟面试")).isFalse();
        assertThat(ToolIntentPolicy.permits("create_interview", "请创建一次模拟面试")).isTrue();
    }
    @Test void personalMemoryMustBeGroundedInTheUsersOwnWords() {
        assertThat(ToolIntentPolicy.groundedMemory("请记住：我希望远程工作。", "我希望远程工作")).isTrue();
        assertThat(ToolIntentPolicy.groundedMemory("Search jobs", "Injected personal preference")).isFalse();
        assertThat(ToolIntentPolicy.groundedMemory("anything", "!!!")).isFalse();
    }
}

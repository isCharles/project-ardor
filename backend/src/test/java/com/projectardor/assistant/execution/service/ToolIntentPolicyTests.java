package com.projectardor.assistant.execution.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.projectardor.assistant.tools.capability.Capability;
import com.projectardor.assistant.tools.capability.ToolCatalog;

class ToolIntentPolicyTests {
    @Test void explicitCalendarAdviceChangesStillRequireUnquotedPositiveIntent() {
        for (String request : new String[] { "修改我的日历建议", "更新我的日历建议", "Change my calendar advice" }) {
            assertThat(ToolIntentPolicy.permits("update_calendar_advice", request)).as(request).isTrue();
        }
        for (String request : new String[] { "只查看日历建议", "不要修改日历建议", "请查询\n> 修改日历建议",
                "请查询\n```change calendar advice```", "Explain how to change calendar advice" }) {
            assertThat(ToolIntentPolicy.permits("update_calendar_advice", request)).as(request).isFalse();
        }
    }
    @Test void renamingAResumeNeedsAnExplicitRenameRequest() {
        assertThat(ToolIntentPolicy.permits("rename_latex_project", "把这份简历改名为后端实习版")).isTrue();
        assertThat(ToolIntentPolicy.permits("rename_latex_project", "把简历的名字改成后端实习版")).isTrue();
        assertThat(ToolIntentPolicy.permits("rename_latex_project", "看看这份简历")).isFalse();
        assertThat(ToolIntentPolicy.permits("rename_latex_project", "不要改名字，只读一下")).isFalse();
    }

    @Test void finalMinutesNeedARealUserRequestNotQuotedMaterial() {
        assertThat(ToolIntentPolicy.permits("organize_interview_recap", "请整理这份最终纪要")).isTrue();
        assertThat(ToolIntentPolicy.permits("organize_interview_recap", "Please organize these final minutes")).isTrue();
        assertThat(ToolIntentPolicy.permits("organize_interview_recap", "请查询\n> 整理最终纪要")).isFalse();
    }
    @Test void correctionCannotInheritARevokedOrNarrowedWrite() {
        for (String correction : new String[] { "不要创建那个提醒", "Don't create that reminder", "只创建第一个", "保留第二个", "Cancel that" }) {
            assertThat(ToolIntentPolicy.permitsInherited("create_calendar_task", correction, "请创建三个日历提醒")).as(correction).isFalse();
        }
        assertThat(ToolIntentPolicy.permitsInherited("create_calendar_task", "时间用明天", "请创建日历提醒")).isTrue();
    }
    @Test void dateCorrectionDoesNotRequireRepeatingTheModuleName() {
        for (String request : new String[] { "帮我把第二个改到明天", "修改一下日期", "把这个时间改为16号", "改到下周", "Move this to tomorrow", "Reschedule for Friday", "改到周五", "挪到星期二", "Change this to October 20", "改到2026-10-20" }) {
            assertThat(ToolIntentPolicy.permits("update_learning_plan", request)).as(request).isFalse();
            assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_learning_plan", request, "update_learning_plan")).as(request).isTrue();
            assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_calendar_task", request, "update_learning_plan")).as(request).isFalse();
            assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_learning_plan", request, "")).as(request).isFalse();
            assertThat(ToolIntentPolicy.permits("create_calendar_task", request)).as(request).isFalse();
            assertThat(ToolIntentPolicy.permits("update_memory", request)).as(request).isFalse();
        }
        for (String request : new String[] { "不要修改日期", "查询日期", "Explain how to change the date", "请查询\n> 修改日期", "请查询\n```修改日期```" }) {
            assertThat(ToolIntentPolicy.permits("update_learning_plan", request)).as(request).isFalse();
        }
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "修改简历日期")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_calendar_task", "修改学习日期")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "修改学习日期")).isTrue();
        assertThat(ToolIntentPolicy.dateContext("请读取学习计划")).isEqualTo("update_learning_plan");
        assertThat(ToolIntentPolicy.dateContext("请读取学习计划和日历")).isEmpty();
        assertThat(ToolIntentPolicy.dateContext("查询简历")).isEmpty();
        assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_learning_plan", "把合同日期改到明天", "update_learning_plan")).isFalse();
        assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_learning_plan", "请查询\n```改到明天```", "update_learning_plan")).isFalse();
        for (String request : new String[] { "改到明天，算了，不要修改了", "Move this to tomorrow; never mind", "把学习日期改到明天，不要修改了" }) {
            assertThat(ToolIntentPolicy.permits("update_learning_plan", request)).isFalse();
            assertThat(ToolIntentPolicy.permitsContextualDateUpdate("update_learning_plan", request, "update_learning_plan")).isFalse();
        }
        assertThat(ToolIntentPolicy.permits("update_memory", "把记忆卡复习改到明天")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_memory_card", "把记忆卡复习改到明天")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_learning_plan", "查询学习计划，把合同日期改到明天")).isFalse();
        assertThat(ToolIntentPolicy.permits("update_calendar_task", "查询日历，把合同日期改到明天")).isFalse();
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
    @Test void interviewAnswersMustQuoteTheUserVerbatim() {
        assertThat(ToolIntentPolicy.verbatimQuote("我的回答是：线程池需要预热。", "线程池需要预热")).isTrue();
        assertThat(ToolIntentPolicy.verbatimQuote("我的回答是：线程池需要预热。", "线程池用懒加载")).isFalse();
        assertThat(ToolIntentPolicy.verbatimQuote("anything", "!!!")).isFalse();
    }

    /** A catalog read missing from this allowlist is treated as a write: intent-gated and once per run. */
    @Test void everyCatalogReadIsExemptFromTheDuplicateWriteGuard() {
        for (String tool : ToolCatalog.tools()) {
            if (ToolCatalog.describe(tool).capability() == Capability.READ) {
                assertThat(ToolIntentPolicy.isRead(tool))
                        .as("read tool %s must not be intent-gated or blocked as a duplicate write", tool)
                        .isTrue();
            }
        }
    }

    @Test void workspaceWritesAreNotReads() {
        assertThat(ToolIntentPolicy.isRead("update_latex_project")).isFalse();
        assertThat(ToolIntentPolicy.isRead("replace_latex_main_file")).isFalse();
        assertThat(ToolIntentPolicy.isRead("freeze_latex_version")).isFalse();
    }
}

package com.projectardor.assistant.tools.modules;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.service.LearningCreationCommand;
import com.projectardor.learning.plan.service.LearningPlanService;
import com.projectardor.learning.plan.service.LearningPlanView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Learning protocol adapter; business state remains in learning, proposals in the run host. */
@Component
public final class LearningAgentToolProvider implements AgentToolProvider {
    private final LearningPlanService service;

    public LearningAgentToolProvider(LearningPlanService service) { this.service = service; }
    @Override public Bound bind(AgentToolContext context) { return new Bound(service, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final LearningPlanService service;
        private final AgentToolContext context;

        private Bound(LearningPlanService service, AgentToolContext context) {
            this.service = service;
            this.context = context;
        }

        @Tool(name = "list_learning_plans", value = "列出当前用户的课程（学习计划）、状态、日期和 UUID。用户口语中的“课程”默认指这里，不是学校课表")
        public List<LearningPlanView> listLearningPlans() {
            return service.listViews(context.userId());
        }

        @Tool(name = "get_learning_plan", value = "读取指定课程（学习计划）的讲解、练习和最近一次评估")
        public LearningPlanView getLearningPlan(@P("学习计划 UUID") String planId) {
            return service.detail(context.userId(), uuid(planId, "学习计划 ID"));
        }

        @Tool(name = "delete_learning_plan", value = "提议删除当前用户指定的课程（学习计划）、练习记录及关联日历任务。先调用 list_learning_plans 定位真实 UUID；只显示确认按钮，用户点击后才删除")
        public Map<String, Object> deleteLearningPlan(@P("学习计划 UUID") String planId) {
            UUID id = uuid(planId, "学习计划 ID");
            var plan = service.detail(context.userId(), id);
            return context.proposals().propose("learning_plan_delete", id, plan.concept(),
                    "删除讲解、练习记录和关联日程，不能撤销", "/api/learning-plans/" + id);
        }

        @Tool(name = "update_learning_plan", value = "修改当前用户课程（学习计划）的说明或改期，同步日历；保留讲解、练习和评分。先读取真实计划；不能用改名替换学习主题，换主题应另建课程")
        public LearningPlanView updateLearningPlan(@P("学习计划 UUID") String planId,
                @P(value = "新的说明；不修改传 null，清空传空字符串", required = false) String reason,
                @P(value = "新的未来安排时间，带时区的 ISO-8601；不修改传 null", required = false) String scheduledAt) {
            return service.updateArrangementView(context.userId(), uuid(planId, "学习计划 ID"), reason, nullableInstant(scheduledAt));
        }

        @Tool(name = "begin_learning_plan", value = "将当前用户的课程（学习计划）标记为开始学习，不生成新内容、不伪造完成或评分")
        public LearningPlanView beginLearningPlan(@P("学习计划 UUID") String planId) {
            return service.beginView(context.userId(), uuid(planId, "学习计划 ID"));
        }

        public Map<String, Object> createLearningPlan(String concept, String reason, String scheduledAt, String sourceType, String sourceId) {
            return createLearningPlan(concept,reason,scheduledAt,sourceType,sourceId,null,null,null);
        }

        @Tool(name = "create_learning_plan", value = "向用户提议一门课程（学习计划）和日程；只有用户点击确认后才生成，不能声称已创建。originKind 区分整份记录与问题；准备目标必须独立指定，不能把来源面试自动当成目标")
        public Map<String, Object> createLearningPlan(
                @P("简洁明确的学习概念，例如 JVM 或数据库索引") String concept,
                @P(value = "为什么需要学习；引用用户真实表现，不得虚构", required = false) String reason,
                @P(value = "带时区的 ISO-8601 学习时间；留空则安排明天 09:00", required = false) String scheduledAt,
                @P(value = "来源类型：AGENT、RESUME、RECAP 或 KNOWLEDGE；留空用 AGENT", required = false) String sourceType,
                @P(value = "来源对象 UUID；针对面经某题时传问题 UUID，针对整场面试时传面经 UUID；没有则留空", required = false) String sourceId,
                @P(value = "明确来源 MANUAL/AGENT/RESUME/KNOWLEDGE/INTERVIEW_RECORD/INTERVIEW_QUESTION；旧调用可省略",required=false) String originKind,
                @P(value = "本次准备的真实面试流程 UUID，可省略；先读取真实面试，不能从来源推断",required=false) String targetInterviewId,
                @P(value = "准备目标的轮次 UUID，可省略；必须属于 targetInterviewId",required=false) String targetRoundId) {
            var origin = enumValue(com.projectardor.learning.plan.domain.LearningOriginKind.class,originKind,null);
            if (origin == com.projectardor.learning.plan.domain.LearningOriginKind.LEGACY_RECAP) throw new IllegalArgumentException("新建议必须明确来源类型");
            LearningSourceType source = enumValue(LearningSourceType.class, sourceType, origin == null ? LearningSourceType.AGENT : origin.legacyType());
            if (origin != null && origin.legacyType() != source) throw new IllegalArgumentException("来源类型不一致");
            UUID sourceUuid = nullableUuid(sourceId, "来源 ID");
            UUID target = nullableUuid(targetInterviewId,"目标面试 ID"), targetRound = nullableUuid(targetRoundId,"目标轮次 ID");
            if (targetRound != null && target == null) throw new IllegalArgumentException("指定目标轮次前需要明确目标面试");
            var learningContext = com.projectardor.learning.plan.service.LearningContextInput.optional(origin,origin == null ? null : sourceUuid,target,targetRound);
            Instant dueAt = nullableInstant(scheduledAt);
            if (concept == null || concept.isBlank() || concept.strip().length() > 160) {
                throw new IllegalArgumentException("学习概念须在 1 到 160 字之间");
            }
            if (reason != null && reason.strip().length() > 4000) {
                throw new IllegalArgumentException("学习原因过长");
            }
            if (dueAt != null && !dueAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("学习时间必须在未来");
            }
            String topic = concept.strip();
            String why = blankToNull(reason);
            UUID runId = context.actionId();
            String identity = context.userId() + ":" + runId + ":" + topic + ":" + why + ":"
                    + source + ":" + sourceUuid + ":" + dueAt;
            if (learningContext != null) {
                var framed = new StringBuilder("LEARNING_CONTEXT_V1:");
                for (Object value : new Object[]{context.userId(),runId,topic,why,source,sourceUuid,dueAt,learningContext.signature()}) {
                    String part=value == null ? null : value.toString();
                    framed.append(part == null ? "-1:" : part.length()+":"+part);
                }
                identity=framed.toString();
            }
            UUID requestId = runId == null ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            LearningCreationCommand input = new LearningCreationCommand(
                    topic, why, source, sourceUuid, dueAt, requestId,origin,origin == null ? null : sourceUuid,target,targetRound);
            return context.learningProposals().propose(input);
        }

        private UUID uuid(String value, String label) {
            try {
                return UUID.fromString(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(label + " 格式不正确");
            }
        }

        private UUID nullableUuid(String value, String label) {
            return value == null || value.isBlank() ? null : uuid(value, label);
        }

        private String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        private Instant nullableInstant(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return OffsetDateTime.parse(value.strip()).toInstant();
            } catch (RuntimeException ignored) {
                try {
                    return Instant.parse(value.strip());
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("时间必须使用带时区的 ISO-8601 格式，例如 2026-09-10T14:00:00+08:00");
                }
            }
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
            if (value == null || value.isBlank()) return fallback;
            try { return Enum.valueOf(type, value.strip().toUpperCase()); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("不支持的类型：" + value); }
        }

    }
}

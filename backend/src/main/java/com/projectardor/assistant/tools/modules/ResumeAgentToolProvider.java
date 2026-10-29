package com.projectardor.assistant.tools.modules;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService;
import com.projectardor.materials.resume.service.ResumeAnalysisQueueService.TaskResult;
import com.projectardor.materials.resume.service.ResumeAnalysisSnapshot;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.materials.resume.service.ResumeView;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Resume adapter only; materials retains source, analysis selection, ownership and job state. */
@Component
public final class ResumeAgentToolProvider implements AgentToolProvider {
    private final ResumeService service;
    private final ResumeAnalysisQueueService queue;
    public ResumeAgentToolProvider(ResumeService service, ResumeAnalysisQueueService queue) {
        this.service = service;
        this.queue = queue;
    }
    @Override public Bound bind(AgentToolContext context) { return new Bound(service, queue, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final ResumeService service;
        private final ResumeAnalysisQueueService queue;
        private final AgentToolContext context;
        private Bound(ResumeService service, ResumeAnalysisQueueService queue, AgentToolContext context) {
            this.service = service; this.queue = queue; this.context = context;
        }

        @Tool(name = "list_resumes", value = "列出当前用户的简历、解析状态、分析任务状态和可用分析 ID")
        public List<ResumeToolView> listResumes() {
            return service.listViews(context.userId()).stream()
                    .map(resume -> ResumeToolView.from(resume, queue.statusIfPresent(context.userId(), resume.id()).orElse(null)))
                    .toList();
        }

        @Tool(name = "analyze_resume", value = "将当前用户已经上传并解析的简历提交到异步分析队列；同一份简历可以按不同岗位 JD 分别分析，重复提交同一目标会复用已有结果")
        public TaskResult analyzeResume(@P("简历 UUID") String resumeId,
                @P(value = "目标岗位名称；通用评估传空字符串", required = false) String targetRole,
                @P(value = "岗位 JD 原文；提供后按该岗位针对性评分，通用评估传空字符串", required = false) String jobDescription,
                @P(value = "岗位大类 ID，例如 engineering；不确定传空字符串", required = false) String roleFamily) {
            return queue.request(context.userId(), uuid(resumeId), targetRole, jobDescription, roleFamily);
        }

        @Tool(name = "get_resume_analysis_status", value = "查询当前用户某份简历的异步分析状态；同一简历有多个岗位任务时用 jobId 查询本次提交")
        public TaskResult getResumeAnalysisStatus(@P("简历 UUID") String resumeId,
                @P(value = "本次提交的 job UUID；只关心最新一次时传空字符串", required = false) String jobId) {
            return queue.status(context.userId(), uuid(resumeId), uuidOrNull(jobId));
        }

        @Tool(name = "get_resume_analysis", value = "读取当前用户一份已经完成的结构化简历分析；同一简历有多份报告时用 analysisId 指定")
        public ResumeAnalysisView getResumeAnalysis(@P("简历 UUID") String resumeId,
                @P(value = "报告 ID；读最新一份传空字符串", required = false) String analysisId) {
            return ResumeAnalysisView.from(service.report(context.userId(), uuid(resumeId), uuidOrNull(analysisId)));
        }

        @Tool(name = "delete_resume", value = "从简历库永久删除当前用户指定简历及其分析。只有用户明确要求删除时使用；先调用 list_resumes 确认目标")
        public Map<String, Object> deleteResume(@P("简历 UUID") String resumeId) {
            UUID id = uuid(resumeId);
            ResumeView resume = service.detail(context.userId(), id);
            return context.proposals().propose("resume", id, resume.originalFilename(),
                    "连同它的分析结果一起删除", "/api/resumes/" + id);
        }

        private UUID uuidOrNull(String value) {
            return value == null || value.isBlank() ? null : uuid(value);
        }

        private UUID uuid(String value) {
            try { return UUID.fromString(value); }
            catch (RuntimeException failure) { throw new IllegalArgumentException("简历 ID 格式不正确"); }
        }
    }

    public record ResumeToolView(UUID resumeId, String filename, String parseStatus, UUID analysisId,
            String analysisStatus, String analysisError, Instant createdAt) {
        public static ResumeToolView from(ResumeView resume, TaskResult task) {
            return new ResumeToolView(resume.id(), resume.originalFilename(), resume.parseStatus().name(),
                    resume.analysisId(), task == null ? null : task.status().name(),
                    task == null ? null : task.errorMessage(), resume.createdAt());
        }
    }

    public record ResumeAnalysisView(UUID analysisId, UUID resumeId, Map<String, Object> analysis, String modelName) {
        public static ResumeAnalysisView from(ResumeAnalysisSnapshot snapshot) {
            return new ResumeAnalysisView(snapshot.id(), snapshot.resumeId(), snapshot.analysis(), snapshot.modelName());
        }
    }
}

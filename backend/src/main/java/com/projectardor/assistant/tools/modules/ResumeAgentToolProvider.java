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

        @Tool(name = "analyze_resume", value = "将当前用户已经上传并解析的简历提交到异步分析队列；重复调用会复用已有结果")
        public TaskResult analyzeResume(@P("简历 UUID") String resumeId) {
            return queue.request(context.userId(), uuid(resumeId));
        }

        @Tool(name = "get_resume_analysis_status", value = "查询当前用户某份简历的异步分析状态")
        public TaskResult getResumeAnalysisStatus(@P("简历 UUID") String resumeId) {
            return queue.status(context.userId(), uuid(resumeId));
        }

        @Tool(name = "get_resume_analysis", value = "读取当前用户一份已经完成的结构化简历分析")
        public ResumeAnalysisView getResumeAnalysis(@P("简历 UUID") String resumeId) {
            return ResumeAnalysisView.from(service.analysisForResume(context.userId(), uuid(resumeId)));
        }

        @Tool(name = "delete_resume", value = "从简历库永久删除当前用户指定简历及其分析。只有用户明确要求删除时使用；先调用 list_resumes 确认目标")
        public Map<String, Object> deleteResume(@P("简历 UUID") String resumeId) {
            UUID id = uuid(resumeId);
            ResumeView resume = service.detail(context.userId(), id);
            return context.proposals().propose("resume", id, resume.originalFilename(),
                    "连同它的分析结果一起删除", "/api/resumes/" + id);
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

package com.projectardor.assistant.tools.modules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.projectardor.materials.latex.service.LatexWorkspaceService;
import com.projectardor.materials.latex.service.LatexWorkspaceService.ProjectView;
import com.projectardor.materials.latex.service.LatexWorkspaceService.RevisionView;
import com.projectardor.materials.latex.service.LatexWorkspaceService.Snapshot;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.ObjectMapper;

/**
 * LaTeX workspace adapter only; the materials module keeps ownership, revision and freeze rules.
 * Editing a resume is a write, so it goes through the same approval path as every other write.
 */
@Component
public final class LatexWorkspaceAgentToolProvider implements AgentToolProvider {

    private final LatexWorkspaceService service;
    private final ObjectMapper mapper;

    public LatexWorkspaceAgentToolProvider(LatexWorkspaceService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override public Bound bind(AgentToolContext context) { return new Bound(service, mapper, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final LatexWorkspaceService service;
        private final ObjectMapper mapper;
        private final AgentToolContext context;

        private Bound(LatexWorkspaceService service, ObjectMapper mapper, AgentToolContext context) {
            this.service = service;
            this.mapper = mapper;
            this.context = context;
        }

        @Tool(name = "list_latex_projects", value = "列出当前用户的简历，含当前修订号与已定稿的正式版本")
        public List<ProjectView> list() {
            return service.list(context.userId());
        }

        @Tool(name = "read_latex_project", value = "读取当前用户某份简历的源码与修订号；未指定修订时读当前修订")
        public Snapshot read(@P("简历 ID") String projectId,
                @P(value = "修订号；读当前修订传空字符串", required = false) String revision) {
            return service.read(context.userId(), uuid(projectId), revision(revision));
        }

        @Tool(name = "read_latex_project_history", value = "列出当前用户某份简历的全部修订，确认要回退或定稿哪一版")
        public List<RevisionView> history(@P("简历 ID") String projectId) {
            return service.history(context.userId(), uuid(projectId));
        }

        @Tool(name = "update_latex_project", value = "把修改后的简历源码保存为新修订；必须带上提交所基于的修订号，冲突时不会覆盖别人的修改")
        public Snapshot update(@P("简历 ID") String projectId,
                @P("本次修改所基于的修订号") String baseRevision,
                @P("文件名到源码的 JSON 对象，必须包含 main.tex，例如 {\"main.tex\":\"\\\\documentclass...\"}") String filesJson,
                @P(value = "这次改了什么，例如：把项目经历改用 STAR 结构", required = false) String message) {
            return service.save(context.userId(), uuid(projectId), number(baseRevision), files(filesJson), message);
        }

        @Tool(name = "replace_latex_main_file", value = "只替换 main.tex 的完整源码并保存为新修订；其他文件保持不变，适合单文件简历的整段重写")
        public Snapshot replaceMain(@P("简历 ID") String projectId,
                @P("本次修改所基于的修订号") String baseRevision,
                @P("main.tex 的完整新源码") String source,
                @P(value = "这次改了什么", required = false) String message) {
            UUID id = uuid(projectId);
            Snapshot current = service.read(context.userId(), id, null);
            Map<String, String> files = new LinkedHashMap<>(current.files());
            files.put(current.mainFile(), source == null ? "" : source);
            return service.save(context.userId(), id, number(baseRevision), files, message);
        }

        @Tool(name = "rename_latex_project", value = "给当前用户的某份简历改名；只改标题，不改内容与修订")
        public ProjectView rename(@P("简历 ID") String projectId,
                @P("新的简历标题") String title) {
            return service.rename(context.userId(), uuid(projectId), title);
        }

        @Tool(name = "freeze_latex_version", value = "把某个已存在的修订标记为这份简历的正式版本；正式版本不可覆盖，只有用户确认可以投递时才使用")
        public ProjectView freeze(@P("简历 ID") String projectId,
                @P("要定稿的修订号") String revisionNo) {
            return service.freeze(context.userId(), uuid(projectId), number(revisionNo));
        }

        private Map<String, String> files(String json) {
            if (json == null || json.isBlank()) return Map.of();
            Map<?, ?> raw = mapper.readValue(json, Map.class);
            Map<String, String> parsed = new LinkedHashMap<>();
            raw.forEach((key, value) -> parsed.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
            return parsed;
        }

        private Integer revision(String value) {
            return value == null || value.isBlank() ? null : number(value);
        }

        private int number(String value) {
            try { return Integer.parseInt(value.trim()); }
            catch (RuntimeException failure) { throw new IllegalArgumentException("修订号必须是数字"); }
        }

        private UUID uuid(String value) {
            try { return UUID.fromString(value.trim()); }
            catch (RuntimeException failure) { throw new IllegalArgumentException("简历 ID 格式不正确"); }
        }
    }
}

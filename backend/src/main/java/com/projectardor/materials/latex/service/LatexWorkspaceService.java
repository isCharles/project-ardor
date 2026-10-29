package com.projectardor.materials.latex.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.materials.latex.domain.LatexProject;
import com.projectardor.materials.latex.domain.LatexRevision;
import com.projectardor.materials.latex.repository.LatexProjectRepository;
import com.projectardor.materials.latex.repository.LatexRevisionRepository;

/**
 * Server-side LaTeX workspace (docs/LOCAL_RESUME_WORKSPACE.md phase 1). A project owns immutable
 * revisions; saving never overwrites one, and only an explicit freeze marks a version as official.
 * Files are plain text kept in the database, so compilation can happen in an isolated directory
 * without any access to the user disk.
 */
@Service
public class LatexWorkspaceService {

    public static final String DEFAULT_MAIN_FILE = "main.tex";
    private static final int MAX_FILES = 40;
    private static final int MAX_TOTAL_CHARS = 400_000;
    private static final int MAX_TITLE_CHARS = 160;
    private static final int MAX_MESSAGE_CHARS = 240;
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]{0,159}");
    private static final Set<String> ALLOWED_SUFFIXES =
            Set.of(".tex", ".cls", ".sty", ".bib", ".txt", ".md", ".cfg");

    private static final String TEMPLATE = """
            % 示例模板：以下内容均为虚构占位，替换成真实信息后再投递。
            \\documentclass[11pt]{article}
            \\usepackage[a4paper,left=0.7in,right=0.7in,top=0.5in,bottom=0.5in,nohead]{geometry}
            \\usepackage{fontspec}
            \\usepackage{xeCJK}
            \\usepackage{titlesec}
            \\usepackage{enumitem}
            \\usepackage[hidelinks]{hyperref}
            \\setmainfont{texgyretermes-regular.otf}[BoldFont=texgyretermes-bold.otf,ItalicFont=texgyretermes-italic.otf,BoldItalicFont=texgyretermes-bolditalic.otf]
            \\setCJKmainfont[AutoFakeBold=2]{Noto Serif CJK SC}
            \\setCJKsansfont[AutoFakeBold=2]{Noto Sans CJK SC}
            \\setCJKmonofont{Noto Sans Mono CJK SC}
            \\setlength{\\parindent}{0pt}
            \\titleformat{\\section}{\\large\\bfseries}{}{0em}{}[\\vspace{-0.5em}\\rule{\\linewidth}{0.6pt}]
            \\titlespacing*{\\section}{0pt}{2.2ex}{1ex}
            \\setlist[itemize]{topsep=0.35ex, itemsep=0.2ex, leftmargin=1.6em}
            \\newcommand{\\name}[1]{\\begin{center}{\\Huge #1}\\end{center}\\vspace{0.6ex}}
            \\newcommand{\\contact}[1]{\\begin{center}\\small #1\\end{center}\\vspace{0.6ex}}
            \\newcommand{\\entry}[2]{\\par\\noindent #1 \\hfill #2\\par\\vspace{0.4ex}}
            \\begin{document}
            \\pagenumbering{gobble}
            \\name{示例姓名}
            \\contact{138-0000-0000（微信）\\quad·\\quad example@ardor.invalid\\quad·\\quad 北京\\quad·\\quad github.com/example}
            \\begin{center}\\small 求职方向：后端开发（可立即到岗）\\end{center}
            \\vspace{1ex}

            \\section{教育背景}
            \\entry{\\textbf{示例大学}，计算机科学与技术，\\textit{2028 届硕士}}{2025.09 -- 2028.06}
            \\begin{itemize}
            \\item 示例：推免入学，研究方向为分布式系统与数据库
            \\end{itemize}
            \\entry{\\textbf{示例大学}，软件工程，\\textit{工学学士}}{2021.09 -- 2025.06}
            \\begin{itemize}
            \\item 示例：专业排名前 10\\%，通过 CET-6
            \\end{itemize}

            \\section{技术能力}
            \\begin{itemize}
            \\item \\textbf{编程语言：}Java、Python、SQL
            \\item \\textbf{后端开发：}Spring Boot、PostgreSQL、Redis、消息队列
            \\item \\textbf{Agent 开发：}LangChain4j、工具调用、RAG 与评测
            \\item \\textbf{工程实践：}Docker、Linux、Git、CI
            \\end{itemize}

            \\section{项目经历}
            \\entry{\\textbf{示例项目｜独立开发}}{2026.01 -- 2026.06}
            技术栈：Java 21、Spring Boot、PostgreSQL
            \\begin{itemize}
            \\item 示例：负责从需求拆解到上线的完整流程，接口 P99 从 [X] ms 降到 [Y] ms
            \\item 示例：为用户隔离与幂等设计数据库约束，异常重试不会产生重复数据
            \\end{itemize}

            \\section{实习经历}
            \\entry{\\textbf{示例科技｜后端开发实习生}}{2025.06 -- 2025.09}
            \\begin{itemize}
            \\item 示例：维护 [业务模块]，补充监控与告警，定位并修复 [问题]
            \\end{itemize}

            \\section{其他}
            \\begin{itemize}
            \\item 示例：在 [竞赛/论文/开源] 中获得 [结果]
            \\end{itemize}
            \\end{document}
            """;

    private final LatexProjectRepository projects;
    private final LatexRevisionRepository revisions;

    public LatexWorkspaceService(LatexProjectRepository projects, LatexRevisionRepository revisions) {
        this.projects = projects;
        this.revisions = revisions;
    }

    @Transactional
    public Snapshot create(UUID userId, String title, Map<String, String> files, String message) {
        Map<String, String> clean = sanitize(files == null || files.isEmpty() ? template() : files);
        LatexProject project = projects.save(LatexProject.create(userId, title(title), DEFAULT_MAIN_FILE));
        LatexRevision revision = revisions.save(
                LatexRevision.create(project.getId(), userId, 1, clean, sha(clean), message(message)));
        return Snapshot.of(project, revision);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list(UUID userId) {
        return projects.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(ProjectView::of).toList();
    }

    @Transactional(readOnly = true)
    public Snapshot read(UUID userId, UUID projectId, Integer revisionNo) {
        LatexProject project = owned(userId, projectId);
        int number = revisionNo == null ? project.getCurrentRevision() : revisionNo;
        return Snapshot.of(project, revision(userId, projectId, number));
    }

    @Transactional(readOnly = true)
    public List<RevisionView> history(UUID userId, UUID projectId) {
        owned(userId, projectId);
        return revisions.findByProjectIdAndUserIdOrderByRevisionNoDesc(projectId, userId).stream()
                .map(RevisionView::of).toList();
    }

    /** A save is an append against an exact base revision; a stale base is a conflict, never a merge. */
    @Transactional
    public Snapshot save(UUID userId, UUID projectId, int baseRevision, Map<String, String> files, String message) {
        LatexProject project = owned(userId, projectId);
        if (baseRevision != project.getCurrentRevision()) throw conflict();
        Map<String, String> clean = sanitize(files);
        int next = project.getCurrentRevision() + 1;
        LatexRevision revision;
        try {
            revision = revisions.saveAndFlush(
                    LatexRevision.create(projectId, userId, next, clean, sha(clean), message(message)));
        } catch (DataIntegrityViolationException parallelSave) {
            throw conflict();
        }
        project.advanceTo(next);
        return Snapshot.of(project, revision);
    }

    @Transactional
    public ProjectView freeze(UUID userId, UUID projectId, int revisionNo) {
        LatexProject project = owned(userId, projectId);
        revision(userId, projectId, revisionNo);
        project.freeze(revisionNo);
        return ProjectView.of(project);
    }

    public static Map<String, String> template() {
        return Map.of(DEFAULT_MAIN_FILE, TEMPLATE);
    }

    private LatexProject owned(UUID userId, UUID projectId) {
        return projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("工作区不存在"));
    }

    private LatexRevision revision(UUID userId, UUID projectId, int revisionNo) {
        return revisions.findByProjectIdAndUserIdAndRevisionNo(projectId, userId, revisionNo)
                .orElseThrow(() -> new ResourceNotFoundException("该修订不存在"));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "工作区已被更新，请基于最新修订重新提交");
    }

    /**
     * Only text files with a relative, simple path inside a fixed suffix set are accepted; anything
     * that could escape the compile directory or smuggle binary content is rejected outright.
     */
    static Map<String, String> sanitize(Map<String, String> files) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("工作区至少需要一个文件");
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("文件数超过上限");
        Map<String, String> clean = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey() == null ? "" : entry.getKey().trim().replace('\\', '/');
            if (!SAFE_PATH.matcher(path).matches() || path.contains("..") || path.startsWith("/")) {
                throw new IllegalArgumentException("不支持的路径：" + path);
            }
            int dot = path.lastIndexOf('.');
            String suffix = dot < 0 ? "" : path.substring(dot).toLowerCase();
            if (!ALLOWED_SUFFIXES.contains(suffix)) throw new IllegalArgumentException("不支持的文件类型：" + path);
            String content = entry.getValue() == null ? "" : entry.getValue();
            total += content.length();
            clean.put(path, content);
        }
        if (!clean.containsKey(DEFAULT_MAIN_FILE)) throw new IllegalArgumentException("缺少 " + DEFAULT_MAIN_FILE);
        if (total > MAX_TOTAL_CHARS) throw new IllegalArgumentException("工作区内容超过上限");
        return Map.copyOf(clean);
    }

    static String sha(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(files).forEach((path, content) ->
                    digest.update((path + "\u0000" + content + "\u0000").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String title(String value) {
        String normalized = value == null || value.isBlank() ? "我的简历" : value.trim();
        return normalized.length() > MAX_TITLE_CHARS ? normalized.substring(0, MAX_TITLE_CHARS) : normalized;
    }

    private static String message(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > MAX_MESSAGE_CHARS ? trimmed.substring(0, MAX_MESSAGE_CHARS) : trimmed;
    }

    public record ProjectView(UUID id, String title, String mainFile, int currentRevision,
            Integer frozenRevision, Instant createdAt, Instant updatedAt) {
        static ProjectView of(LatexProject project) {
            return new ProjectView(project.getId(), project.getTitle(), project.getMainFile(),
                    project.getCurrentRevision(), project.getFrozenRevision(),
                    project.getCreatedAt(), project.getUpdatedAt());
        }
    }

    public record RevisionView(int revisionNo, String sha256, String message, Instant createdAt) {
        static RevisionView of(LatexRevision revision) {
            return new RevisionView(revision.getRevisionNo(), revision.getSha256(), revision.getMessage(),
                    revision.getCreatedAt());
        }
    }

    public record Snapshot(UUID projectId, String title, String mainFile, int revisionNo,
            Map<String, String> files, String sha256, Integer frozenRevision, Instant createdAt) {
        static Snapshot of(LatexProject project, LatexRevision revision) {
            return new Snapshot(project.getId(), project.getTitle(), project.getMainFile(),
                    revision.getRevisionNo(), revision.getFiles(), revision.getSha256(),
                    project.getFrozenRevision(), revision.getCreatedAt());
        }
    }
}

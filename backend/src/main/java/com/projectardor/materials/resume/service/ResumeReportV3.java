package com.projectardor.materials.resume.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** User-facing v3 report content. Scoring rules stay in ResumeScoringRubricV3. */
public final class ResumeReportV3 {
    public record Issue(String severity, String dimensionId, String category, String problem,
                        String why, String action, String benchmark, String evidence, String example) {}

    private static final Set<String> SEVERITIES = Set.of("A", "B", "C");
    private static final List<String> EXTRACTION_ARTIFACTS = List.of(
            "断行", "换行", "连字符", "拆词", "被拆", "拆开", "空格不一致", "中英混排", "中英文混排",
            "pdf", "扫描", "字体", "字号", "排版", "页边距", "分页", "加粗", "斜体");
    private ResumeReportV3() {}

    public static String summary(JsonNode node) {
        return text(node, "summary", 2000);
    }

    public static String background(JsonNode node) {
        return text(node, "background", 3000);
    }

    public static List<String> strengths(JsonNode input) {
        if (input == null || input.isNull()) return List.of();
        if (!input.isArray() || input.size() > 2) throw invalid("strengths 必须是最多 2 条的数组");
        List<String> result = new ArrayList<>();
        for (JsonNode node : input) {
            if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 800) throw invalid("strengths 存在非法条目");
            result.add(node.textValue().strip());
        }
        return List.copyOf(result);
    }

    public static List<Issue> issues(JsonNode input, String resumeText) {
        if (input == null || input.isNull()) return List.of();
        if (!input.isArray() || input.size() > 9) throw invalid("issues 必须是最多 9 条的数组");
        String source = ResumeScoringRubricV3.normalize(resumeText);
        List<Issue> result = new ArrayList<>();
        for (JsonNode node : input) {
            if (node == null || !node.isObject()) throw invalid("issues 存在非对象项");
            String severity = text(node, "severity", 1);
            String dimensionId = text(node, "dimensionId", 60);
            String evidence = optionalText(node, "evidence", 3000);
            String example = optionalText(node, "example", 3000);
            String benchmark = cleanStandard(optionalText(node, "benchmark", 2000));
            if (!SEVERITIES.contains(severity)
                    || ResumeScoringRubricV3.DIMENSIONS.stream().noneMatch(d -> d.id().equals(dimensionId))
                    || (!evidence.isEmpty() && !ResumeScoringRubricV3.quoteAppears(source, evidence))) {
                throw invalid("问题项 severity/dimensionId/evidence 不合法");
            }
            Issue issue = new Issue(severity, dimensionId, text(node, "category", 120), text(node, "problem", 2000),
                    cleanPrefix(optionalText(node, "why", 2000), "为什么是问题", "原因", "影响"),
                    cleanPrefix(text(node, "action", 2000), "怎么改", "修改建议", "建议"),
                    benchmark, evidence, example);
            if (!extractionArtifact(issue)) result.add(issue);
        }
        result.sort(Comparator.comparingInt((Issue issue) -> List.of("A", "B", "C").indexOf(issue.severity())));
        return List.copyOf(result);
    }

    private static String cleanStandard(String value) {
        String cleaned = cleanPrefix(value, "100 分标准", "100分标准", "顶级标准", "满分标准", "评分标准");
        return cleaned.isBlank() ? "" : cleaned;
    }

    private static String cleanPrefix(String value, String... prefixes) {
        String cleaned = value == null ? "" : value.strip();
        for (String prefix : prefixes) {
            String pattern = "(?i)^\\*{0,2}" + java.util.regex.Pattern.quote(prefix) + "\\*{0,2}\\s*[:：]\\s*";
            cleaned = cleaned.replaceFirst(pattern, "").strip();
        }
        return cleaned;
    }

    private static boolean extractionArtifact(Issue issue) {
        String text = (issue.category() + " " + issue.problem() + " " + issue.why() + " "
                + issue.action() + " " + issue.benchmark()).toLowerCase(java.util.Locale.ROOT);
        return EXTRACTION_ARTIFACTS.stream().anyMatch(text::contains);
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > max || value.textValue().isBlank()) {
            throw invalid("字段 " + field + " 缺失或格式错误");
        }
        return value.textValue().strip();
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > max) throw invalid("字段 " + field + " 格式错误");
        return value.textValue().strip();
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("简历报告内容不符合 v3 规范：" + detail);
    }
}

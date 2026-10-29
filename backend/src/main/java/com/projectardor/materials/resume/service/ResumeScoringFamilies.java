package com.projectardor.materials.resume.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fixed role-family standards. Weights stay identical across families so scores
 * remain comparable; a family only changes the market baseline and the kind of
 * evidence expected for a few dimensions. JD text never changes these rules.
 */
public final class ResumeScoringFamilies {
    public static final String VERSION = "ardor-resume-families-v1";
    public static final String DEFAULT_ID = "general";

    public record Family(String id, String en, String zh, Map<String, String> benchmarks,
                         Map<String, String> evidenceFocus) {}

    public static final List<Family> FAMILIES = List.of(
            new Family("general", "General technology", "通用技术",
                    benchmarks("B", "B", "B", "B", "B", "C"),
                    Map.of(
                            "technicalDepth", "可验证的专业深度：具体技术选择、实现边界与取舍",
                            "roleFit", "按简历声明的方向判断，不假设特定岗位")),
            new Family("engineering", "Software engineering", "开发",
                    benchmarks("B", "B", "B", "B", "B", "C"),
                    Map.of(
                            "technicalDepth", "生产环境代码、架构取舍、故障/性能/复杂度证据",
                            "outcomes", "延迟、QPS、稳定性、成本或交付周期等可验证技术结果",
                            "roleFit", "与目标技术栈和工程职责的直接证据")),
            new Family("algorithm", "Algorithm / AI", "算法",
                    benchmarks("A", "B", "B", "B", "A", "B"),
                    Map.of(
                            "technicalDepth", "模型/算法选择、数据规模、实验严谨性、离线与线上指标",
                            "outcomes", "指标提升幅度、对照实验、评估方法或论文/竞赛结果",
                            "roleFit", "目标算法方向的核心能力证据，关键词不算")),
            new Family("data", "Data", "数据",
                    benchmarks("B", "B", "B", "B", "B", "C"),
                    Map.of(
                            "technicalDepth", "SQL/Python、数据管道、统计或实验方法的实际使用",
                            "outcomes", "业务指标、AB 实验、分析结论对决策的影响",
                            "roleFit", "目标数据岗位的分析链条和业务理解证据")),
            new Family("product", "Product", "产品",
                    benchmarks("B", "B", "B", "A", "B", "C"),
                    Map.of(
                            "technicalDepth", "问题定义、需求优先级、方案取舍和跨团队推进",
                            "outcomes", "用户/业务指标变化、上线结果和验证方式",
                            "roleFit", "目标产品方向的问题域和用户理解证据")),
            new Family("sales", "Sales / business", "销售",
                    benchmarks("B", "A", "B", "A", "B", "C"),
                    Map.of(
                            "technicalDepth", "客户理解、销售流程、方案设计和谈判能力",
                            "outcomes", "配额/营收/回款/转化等明确数字，以及客户与周期",
                            "roleFit", "目标行业、客单价和销售模式的匹配证据")),
            new Family("management", "Management / delivery", "管理",
                    benchmarks("B", "B", "A", "B", "B", "B"),
                    Map.of(
                            "technicalDepth", "团队/项目管理方法、组织设计和跨团队协作",
                            "outcomes", "团队规模、交付结果、人员成长和业务影响",
                            "roleFit", "目标管理范围（团队规模、职能、复杂度和汇报线）的证据")));

    /**
     * A pasted JD already says what the job is, so the caller should not ask the
     * user for a family in that case. Specific families are checked before the
     * broad engineering bucket ("数据开发" is data, not generic engineering).
     */
    private static final List<Map.Entry<String, List<String>>> INFERENCE = List.of(
            Map.entry("algorithm", List.of("算法", "机器学习", "深度学习", "大模型", "模型训练", "模型推理",
                    "llm", "nlp", "自然语言", "计算机视觉", "推荐系统", "搜索排序", "pytorch", "tensorflow", "cuda", "强化学习")),
            // Data must come from the role itself. Shared technologies such as SQL,
            // MySQL or PostgreSQL appear in ordinary backend JDs and cannot classify one.
            Map.entry("data", List.of("数据分析", "数据开发", "数据工程", "数据仓库", "数仓", "数据科学", "数据平台",
                    "数据挖掘", "etl", "ab 实验", "a/b", "指标体系", "tableau", "数据治理", "大数据")),
            Map.entry("product", List.of("产品经理", "产品设计", "需求分析", "用户研究", "交互设计", "原型", "prd")),
            Map.entry("sales", List.of("销售", "客户经理", "商务", "渠道", "营收", "回款", "大客户")),
            Map.entry("management", List.of("技术经理", "团队管理", "项目管理", "交付经理", "总监", "pmo", "团队负责人")),
            Map.entry("engineering", List.of("工程师", "开发", "后端", "前端", "服务端", "客户端", "java",
                    "golang", "python", "spring", "react", "vue", "node", "微服务", "架构", "devops", "sre",
                    "测试", "android", "ios", "全栈", "运维", "c++", "rust")));

    public static String infer(String description) {
        // Only the JD decides: a bare role name is not enough evidence, and the
        // caller still selects a family when it has no JD at all.
        String text = (description == null ? "" : description).toLowerCase(Locale.ROOT);
        if (text.isBlank()) return DEFAULT_ID;
        for (var rule : INFERENCE) {
            if (rule.getValue().stream().anyMatch(text::contains)) return rule.getKey();
        }
        return DEFAULT_ID;
    }

    private ResumeScoringFamilies() {}

    public static Family resolve(String id) {
        if (id == null || id.isBlank()) return byId(DEFAULT_ID);
        return FAMILIES.stream().filter(family -> family.id().equals(id)).findFirst().orElse(byId(DEFAULT_ID));
    }

    public static Family byId(String id) {
        return FAMILIES.stream().filter(family -> family.id().equals(id)).findFirst().orElseThrow();
    }

    public static boolean isKnown(String id) {
        return id != null && FAMILIES.stream().anyMatch(family -> family.id().equals(id));
    }

    public static String prompt(Family family) {
        StringBuilder prompt = new StringBuilder("本次目标大类：").append(family.zh()).append("（").append(family.id()).append("）。\n");
        prompt.append("六维权重固定，不因大类改变；只按下列针对性证据要求判断，不得放宽等级。\n");
        prompt.append("市场基准线（内部参考，不要输出）：\n");
        family.benchmarks().forEach((dimension, grade) -> prompt.append("- ").append(dimension).append(": ").append(grade).append('\n'));
        prompt.append("本大类针对性证据要求：\n");
        family.evidenceFocus().forEach((dimension, focus) -> prompt.append("- ").append(dimension).append(": ").append(focus).append('\n'));
        return prompt.toString();
    }

    private static Map<String, String> benchmarks(String depth, String outcomes, String ownership,
                                                  String clarity, String roleFit, String background) {
        return Map.of(
                "technicalDepth", depth,
                "outcomes", outcomes,
                "ownership", ownership,
                "clarity", clarity,
                "roleFit", roleFit,
                "background", background);
    }
}

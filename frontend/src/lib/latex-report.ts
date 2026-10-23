export type ReportAnalysis = Record<string, unknown>;

const DIMENSIONS: Array<[string, string]> = [
  ["technicalDepth", "专业深度"],
  ["outcomes", "项目成果"],
  ["ownership", "工程能力"],
  ["clarity", "表达结构"],
  ["roleFit", "岗位匹配"],
  ["background", "背景竞争力"],
];

const SEVERITY_SECTIONS: Array<[string, string]> = [
  ["A", "A 区 · 最该改"],
  ["B", "B 区 · 应该改"],
  ["C", "C 区 · 可增色"],
];

function escapeLatex(value: unknown): string {
  const text = value == null ? "" : String(value);
  // One pass: never escape braces/backslashes introduced by an earlier replacement.
  return text.replace(/[\\{}$&%#_^~]/g, (character) => {
    if (character === "\\") return "\\textbackslash{}";
    if (character === "^") return "\\textasciicircum{}";
    if (character === "~") return "\\textasciitilde{}";
    return "\\" + character;
  });
}

function inline(value: unknown): string {
  // The scoring model emits **bold** markers; keep them meaningful in the PDF.
  return escapeLatex(value).replace(/\*\*([^*]+)\*\*/g, "\\textbf{$1}");
}

function paragraph(value: unknown): string {
  const lines = String(value ?? "").split(/\r?\n+/).map((line) => line.trim()).filter(Boolean);
  return lines.map(inline).join("\\par\n");
}

function recordList(value: unknown): ReportAnalysis[] {
  return Array.isArray(value)
    ? value.filter((item): item is ReportAnalysis => !!item && typeof item === "object")
    : [];
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function tableRows(analysis: ReportAnalysis): string {
  const grades = analysis.dimensionGrades && typeof analysis.dimensionGrades === "object"
    ? analysis.dimensionGrades as Record<string, unknown>
    : {};
  const benchmarks = analysis.dimensionBenchmarks && typeof analysis.dimensionBenchmarks === "object"
    ? analysis.dimensionBenchmarks as Record<string, unknown>
    : {};
  return DIMENSIONS.map(([id, label]) => {
    const grade = escapeLatex(grades[id] ?? "F");
    const market = escapeLatex(benchmarks[id] ?? "B");
    const below = (["S", "A", "B", "C", "D", "E", "F"].indexOf(String(grades[id] ?? "F")) > ["S", "A", "B", "C", "D", "E", "F"].indexOf(String(benchmarks[id] ?? "B")));
    return `${label} & ${grade} & ${market} & ${below ? "未达市场要求" : "达标"} \\\\`;
  }).join("\n");
}

function issueBlock(issue: ReportAnalysis, index: number): string {
  const fields: Array<[string, string]> = [
    ["问题", inline(issue.problem)],
    ["为什么重要", paragraph(issue.why)],
    ["目标", paragraph(issue.benchmark)],
    ["原文", paragraph(issue.evidence)],
    ["建议改法", paragraph(issue.action)],
    ["改写示例", paragraph(issue.example)],
  ];
  const body = fields
    .filter(([, value]) => value.trim().length > 0)
    .map(([label, value]) => `\\fieldlabel{${label}}${value}`)
    .join("\n\n");
  return `\\needspace{10\\baselineskip}
\\subsection*{${index}. ${escapeLatex(issue.category || "问题")}}

${body}
`;
}

export function buildLatexReport(filename: string, analysis: ReportAnalysis): string {
  const score = numberValue(analysis.overallScore);
  const context = analysis.scoringContext && typeof analysis.scoringContext === "object"
    ? analysis.scoringContext as ReportAnalysis
    : {};
  const targetRole = stringValue(context.targetRole);
  const summary = paragraph(analysis.summary);
  const background = paragraph(analysis.background);
  const issues = recordList(analysis.issues);
  const strengths = Array.isArray(analysis.strengths)
    ? analysis.strengths.filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    : [];

  const issueSections = SEVERITY_SECTIONS.map(([severity, title]) => {
    const selected = issues.filter((issue) => issue.severity === severity);
    if (selected.length === 0) return "";
    const blocks = selected.map((issue, index) => issueBlock(issue, index + 1)).join("\n");
    return `\\needspace{12\\baselineskip}
\\section{${title}}

${blocks}`;
  }).filter(Boolean).join("\n");

  const strengthsSection = strengths.length > 0
    ? `\\section{优点}

\\begin{itemize}
${strengths.map((strength) => `\\item ${inline(strength)}`).join("\n")}
\\end{itemize}
`
    : "";

  return `\\documentclass[11pt]{article}

\\usepackage[a4paper,top=25mm,bottom=25mm,left=25mm,right=25mm]{geometry}
\\usepackage{fontspec}
\\usepackage{xeCJK}
\\IfFontExistsTF{SimSun}{\\setCJKmainfont[AutoFakeBold=2.5]{SimSun}}{\\setCJKmainfont[AutoFakeBold=2.5]{Noto Serif CJK SC}}
\\IfFontExistsTF{SimHei}{\\setCJKsansfont{SimHei}}{\\setCJKsansfont{Noto Sans CJK SC}}
\\IfFontExistsTF{SimHei}{\\setCJKfamilyfont{heiti}[AutoFakeBold=3]{SimHei}}{\\setCJKfamilyfont{heiti}[AutoFakeBold=3]{Noto Sans CJK SC}}
\\usepackage{booktabs}
\\usepackage{needspace}
\\usepackage[hidelinks]{hyperref}

\\newcommand{\\fieldlabel}[1]{{\\CJKfamily{heiti}\\bfseries #1：}}
\\setlength{\\parindent}{2em}
\\setlength{\\parskip}{0.35em}
\\linespread{1.25}
\\pagestyle{plain}

\\begin{document}

\\title{简历诊断报告}
\\author{Ardor}
\\date{${escapeLatex(new Date().toLocaleDateString("zh-CN", { year: "numeric", month: "long" }))}}
\\maketitle

\\begin{center}
\\textbf{${escapeLatex(filename)}}\\\\[0.3em]
${targetRole ? `目标岗位：${inline(targetRole)}` : "本报告为通用化分析"}
\\end{center}

\\section{总体评价}

当前竞争力：\\textbf{${score == null ? "—" : escapeLatex(Math.round(score))} / 100}。

${summary}

${background}

\\section{能力雷达}

\\begin{center}
\\begin{tabular}{lccc}
\\toprule
维度 & 你的等级 & 市场要求 & 结论 \\\\
\\midrule
${tableRows(analysis)}
\\bottomrule
\\end{tabular}
\\end{center}

${issueSections}
${strengthsSection}
\\end{document}
`;
}

import http from "node:http";

// Opt-in recovery fixture guard. Never supply real credentials to this server.
const expectedApiKey = process.env.ARDOR_FAKE_EXPECTED_API_KEY;
if (expectedApiKey && expectedApiKey !== "fake-only") throw new Error("Only the synthetic recovery key is supported");

const analysis = {
  overallScore: 82,
  categoryScores: { content: 84, impact: 76, clarity: 85, roleFit: 83 },
  summary: "技术栈聚焦，项目经历清晰；补充量化结果后会更有说服力。",
  background: "合成测试资料，不推断真实学校、学历或就业结果。",
  dimensionRatings: ["technicalDepth", "outcomes", "ownership", "clarity", "roleFit", "background"].map(id => ({ id, level: 0, evidence: "", rationale: "Synthetic fixture has no verified dimension evidence" })),
  issues: [{ severity: "B", dimensionId: "outcomes", category: "成果证据", problem: "缺少核实过的结果口径", why: "无法判断个人行动带来的效果", action: "补充自己能够核实的基线和结果", benchmark: "有可核验的基线与验收方法", evidence: "", example: "使用[待核实的指标]，不编造经历" }],
  education: ["计算机相关专业"],
  experience: ["Java 后端开发经历"],
  projects: ["Spring Boot 服务项目"],
  skills: ["Java", "Spring Boot", "PostgreSQL"],
  strengths: ["后端技术栈聚焦", "有可验证的项目经验"],
  weaknesses: ["缺少量化业务结果"],
  possibleTargetRoles: ["Java 后端工程师"],
  criterionRatings: ["completeness", "timeline", "responsibilities", "ownership", "outcomes", "verification", "structure", "precision", "consistency", "requiredSkills", "appliedSkills", "scopeFit"].map(id => ({ id, checks: [false, false, false, false], evidence: "", rationale: "Synthetic fixture has no verified criterion evidence" })),
  recommendations: [{ priority: "P1", criterionId: "outcomes", evidence: "", problem: "缺少核实过的结果口径", action: "补充自己能够核实的基线和结果", example: "将[待核实的基线]改进至[待核实的结果]" }],
};

const questions = {
  questions: [
    { questionText: "请介绍你在 Spring Boot 项目中负责的核心模块。", questionType: "PROJECT", evaluationCriteria: ["职责清晰", "结果具体"] },
    { questionText: "如何设计一个可靠的用户登录与会话方案？", questionType: "TECHNICAL", evaluationCriteria: ["安全性", "状态管理"] },
    { questionText: "遇到线上故障时你如何定位并推进恢复？", questionType: "BEHAVIORAL", evaluationCriteria: ["排障思路", "沟通协作"] },
  ],
};

const evaluation = {
  overallScore: 86,
  strengths: ["回答结构清晰", "能说明技术取舍"],
  weaknesses: ["量化结果仍可加强"],
  knowledgeGaps: ["可补充高并发容量规划"],
  communicationIssues: ["结论可以更前置"],
  suggestedNextSteps: ["准备两个 STAR 项目案例", "复习会话安全与故障演练"],
};

const recap = {
  title: "Java 后端一面复盘",
  company: "Ardor Labs",
  targetRole: "Java 后端工程师",
  occurredAt: "2026-09-02T14:00:00+08:00",
  overview: "围绕项目职责、Redis 与故障定位展开；项目表达清楚，Redis 持久化回答存在遗漏。",
  strengths: ["能够说明项目中的实际职责"],
  weaknesses: ["Redis 持久化机制区分不完整"],
  questions: [
    { questionText: "Redis 的 RDB 和 AOF 有什么区别？", candidateAnswer: "只回答了 RDB 是快照。", followUps: ["AOF 如何重写？"], assessment: "覆盖了 RDB 的基本概念，但遗漏 AOF 写后日志、重写与恢复取舍。", performance: "WEAK", weaknessReason: "关键机制和选型取舍缺失。", betterAnswer: "先比较记录方式、恢复完整性、性能成本，再说明混合持久化。", tags: ["Redis"] },
    { questionText: "你负责项目的哪一部分？", candidateAnswer: "说明了登录模块的设计和结果。", followUps: [], assessment: "职责边界和结果较清楚，具体指标仍需代码或数据验证。", performance: "STRONG", weaknessReason: "", betterAnswer: "", tags: ["项目表达"] },
  ],
};

const lesson = {
  lesson: {
    summary: "理解 Redis 持久化的取舍",
    keyPoints: ["RDB 快照", "AOF 日志", "恢复与性能取舍"],
    explanation: "### 恢复取舍\n\n- **RDB** 定期保存快照。\n- **AOF** 记录写命令。\n\n需要结合恢复目标和性能预算选择。",
    example: "对容忍少量数据丢失的缓存可偏重 RDB。",
    pitfalls: ["不要把 AOF 重写当作立即持久化"],
  },
  exercises: [
    { question: "哪项描述 AOF？", type: "MULTIPLE_CHOICE", options: ["记录写命令", "只保存快照", "不支持重写"], correctOption: 0, rubric: ["记录写命令"] },
    { question: "如何选择 RDB 与 AOF？", type: "SCENARIO", rubric: ["说明恢复目标和性能取舍"] },
  ],
};

const replay = {
  verdict: "CLEARER",
  comparison: "这次补充了 AOF 的机制，但仍需要说明恢复取舍。",
  improvements: ["解释了 AOF 写命令日志"],
  remainingGaps: ["缺少恢复时间取舍"],
  nextChallenge: "故障恢复时如何选择 RDB 与 AOF？",
};

const transientAttempts = new Map();
let delayedReplayRequests = 0;
let delayedInterviewRequests = 0;
let restartRequests = 0;
let pendingLearningResponses = [];
let learningFallbackTimer;

function releaseLearningResponses() {
  if (learningFallbackTimer) clearTimeout(learningFallbackTimer);
  learningFallbackTimer = undefined;
  const responses = pendingLearningResponses;
  pendingLearningResponses = [];
  responses.forEach((respond) => respond());
}

function completionFor(payload) {
  const input = JSON.stringify(payload);
  if (input.includes("面试初步纪要：只整理事实")) {
    if (["recap-recovery-probe", "recap-ui-recovery-probe"].includes(payload.model)) {
      const attempt = (transientAttempts.get(payload.model) ?? 0) + 1;
      transientAttempts.set(payload.model, attempt);
      if (attempt === 1) return { minutes: "" };
    }
    return { minutes: "## Redis 持久化问答\n\n面试官问题：RDB 和 AOF 的区别？\n候选人回答：RDB 是快照，AOF 恢复取舍没有说清楚。\n待确认：转录可能缺少后续追问。" };
  }
  if (input.includes("你负责概括对话标题")) return { title: "Redis 学习规划" };
  if (payload.model === "recap-metadata-probe" && input.includes("面试复盘编辑")) {
    return { ...recap, company: "", targetRole: "", occurredAt: "" };
  }
  if (input.includes("面试复盘编辑")) return recap;
  if (input.includes("面试复盘教练")) return replay;
  if (input.includes("严谨的中文技术导师")) return lesson;
  if (input.includes("严格但有建设性的中文技术教练")) {
    if (payload.model === "async-evaluation-probe") {
      const attempts = (transientAttempts.get(payload.model) ?? 0) + 1;
      transientAttempts.set(payload.model, attempts);
      if (attempts === 1) return { score: -1, feedback: "invalid score" };
    }
    const improved = input.includes("二轮修订");
    return {
      score: improved ? 85 : 65,
      feedback: improved ? "已经说明关键取舍。" : "还需要解释恢复目标。",
      strengths: ["能区分快照与日志"],
      gaps: improved ? [] : ["缺少恢复目标"],
      questionFeedback: [{ question: "如何选择 RDB 与 AOF？", feedback: "结合恢复目标说明即可。" }],
      nextFocus: improved ? "保持练习" : "恢复时间与数据丢失窗口",
      nextExercises: improved ? [] : [
        { question: "选择恢复策略时首先明确什么？", type: "MULTIPLE_CHOICE", options: ["允许的数据丢失窗口", "固定使用快照", "无需考虑性能"], correctOption: 0, rubric: ["允许的数据丢失窗口"] },
        { question: "如何结合恢复时间与数据丢失窗口设计持久化策略？", type: "SCENARIO", rubric: ["分析恢复时间和数据丢失窗口"] },
      ],
    };
  }
  if (input.includes("简历分析器") || (input.includes("简历评估器") && input.includes("dimensionRatings"))) return analysis;
  if (input.includes("技术面试官")) return questions;
  if (input.includes("面试评价官")) return evaluation;
  return { ok: true };
}

http.createServer((request, response) => {
  if (expectedApiKey && request.headers.authorization !== `Bearer ${expectedApiKey}` && request.headers["x-api-key"] !== expectedApiKey) {
    response.writeHead(401, {"content-type": "application/json"});
    response.end(JSON.stringify({error: {message: "Synthetic credential rejected"}}));
    return;
  }
  if (request.url === "/__probe/restart-started") {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({count:restartRequests}));
    return;
  }
  if (request.url === "/__probe/interview-started") {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({ count: delayedInterviewRequests }));
    return;
  }
  if (request.url === "/__probe/replay-started") {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({ count: delayedReplayRequests }));
    return;
  }
  let body = "";
  request.setEncoding("utf8");
  request.on("data", (chunk) => { body += chunk; });
  request.on("end", () => {
    const payload = JSON.parse(body || "{}");
    if (payload.model === "final-minutes-failure-probe") {
      response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "Synthetic normalization failure" } }));
      return;
    }
    if (request.url.endsWith("/embeddings")) {
      const inputs = Array.isArray(payload.input) ? payload.input : [payload.input];
      setTimeout(() => {
        response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
        response.end(JSON.stringify({ object: "list", model: payload.model,
          data: inputs.map((_, index) => ({ object: "embedding", index, embedding: [1, 0.5, 0.25] })),
          usage: { prompt_tokens: 10, total_tokens: 10 } }));
      }, 1500);
      return;
    }
    const delayedReplay = payload.model === "delayed-replay-model" && body.includes("面试复盘教练");
    const concurrentLearning = payload.model === "concurrent-learning-model"
      && body.includes("严格但有建设性的中文技术教练");
    const restartAssessment = payload.model === "restart-ai-probe" && body.includes("严格但有建设性的中文技术教练");
    if (restartAssessment) restartRequests++;
    if (delayedReplay) delayedReplayRequests++;
    if (payload.model === "async-evaluation-probe" && body.includes("面试评价官")) delayedInterviewRequests++;
    const probeTool = payload.tools?.find((tool) => tool.function?.name === "tool_calling_probe");
    const hasToolResult = payload.messages?.some((message) => message.role === "tool");
    const isAgentRequest = payload.tools?.some((tool) => tool.function?.name === "list_resumes");
    if (payload.model === "calendar-advice-module-probe" && isAgentRequest) {
      const names = payload.tools.map(tool => tool.function.name);
      const expected = ["get_calendar_advice", "update_calendar_advice", "delete_calendar_advice"];
      const message = String(payload.messages.filter(item => item.role === "user").at(-1)?.content);
      const mode = message.includes("advice-probe-update") ? "update" : message.includes("advice-probe-delete") ? "delete" : "get";
      const output = String(payload.messages.filter(item => item.role === "tool").at(-1)?.content ?? "");
      if (new Set(names).size !== names.length || expected.some(name => !names.includes(name))
          || hasToolResult && (mode === "get" ? !output.includes("Owner advice fixture") || output.includes("Foreign advice fixture")
            : mode === "update" ? !output.includes("Updated owner advice fixture") : !output.includes("CONFIRMATION_REQUIRED"))) {
        response.writeHead(400, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: { message: "Calendar advice contract or owner isolation failed" } })); return;
      }
      const args = mode === "update" ? { headline: "Updated owner advice fixture", days: ["Updated action"], weeks: [], months: [] } : {};
      const delta = hasToolResult ? { role: "assistant", content: `Synthetic calendar advice ${mode} verified.` }
        : { role: "assistant", tool_calls: [{ index: 0, id: "advice-probe", type: "function",
          function: { name: `${mode}_calendar_advice`, arguments: JSON.stringify(args) } }] };
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      for (const [chunk, finish] of [[delta, null], [{}, hasToolResult ? "stop" : "tool_calls"]]) {
        response.write(`data: ${JSON.stringify({ id: "advice-probe", object: "chat.completion.chunk", model: payload.model,
          choices: [{ index: 0, delta: chunk, finish_reason: finish }] })}\n\n`);
      }
      response.end("data: [DONE]\n\n"); return;
    }
    if (payload.model === "automation-module-probe" && isAgentRequest) {
      const names = payload.tools.map(tool => tool.function.name);
      const expected = ["list_automations", "get_automation", "create_automation", "update_automation", "delete_automation"];
      const message = String(payload.messages.filter(item => item.role === "user").at(-1)?.content);
      const mode = message.includes("automation-probe-update") ? "update" : message.includes("automation-probe-delete") ? "delete" : "list";
      const id = message.match(/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/i)?.[0];
      const output = String(payload.messages.filter(item => item.role === "tool").at(-1)?.content ?? "");
      if (new Set(names).size !== names.length || expected.some(name => !names.includes(name))
          || hasToolResult && (mode === "list" ? !output.includes("Automation owner fixture") || output.includes("Automation foreign fixture")
            : mode === "update" ? !output.includes('"enabled":false') : !output.includes("CONFIRMATION_REQUIRED"))) {
        response.writeHead(400, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: { message: "Automation module contract or owner isolation failed" } })); return;
      }
      const toolName = mode === "list" ? "list_automations" : `${mode}_automation`;
      const args = mode === "list" ? {} : { automationId: id, ...(mode === "update" ? { enabled: false } : {}) };
      const delta = hasToolResult ? { role: "assistant", content: `Synthetic automation ${mode} verified.` }
        : { role: "assistant", tool_calls: [{ index: 0, id: "automation-probe", type: "function", function: { name: toolName, arguments: JSON.stringify(args) } }] };
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      for (const [chunk, finish] of [[delta, null], [{}, hasToolResult ? "stop" : "tool_calls"]]) {
        response.write(`data: ${JSON.stringify({ id: "automation-probe", object: "chat.completion.chunk", model: payload.model, choices: [{ index: 0, delta: chunk, finish_reason: finish }] })}\n\n`);
      }
      response.end("data: [DONE]\n\n"); return;
    }
    if (payload.model === "date-correction-probe" && isAgentRequest) {
      const message = String(payload.messages.filter(item => item.role === "user").at(-1)?.content);
      const planId = message.match(/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/i)?.[0];
      const scheduledAt = message.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z/)?.[0];
      const delta = hasToolResult ? { role: "assistant", content: "Synthetic date correction finished." }
        : { role: "assistant", tool_calls: [{ index: 0, id: "date-correction", type: "function",
          function: { name: "update_learning_plan", arguments: JSON.stringify({ planId, reason: null, scheduledAt }) } }] };
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      for (const [chunk, finish] of [[delta, null], [{}, hasToolResult ? "stop" : "tool_calls"]]) {
        response.write(`data: ${JSON.stringify({ id: "date-correction", object: "chat.completion.chunk", model: payload.model,
          choices: [{ index: 0, delta: chunk, finish_reason: finish }] })}\n\n`);
      }
      response.end("data: [DONE]\n\n"); return;
    }
    if (payload.model === "steering-context-probe" && isAgentRequest) {
      const turns = payload.messages.filter(message => message.role === "user");
      const original = turns.filter(message => String(message.content).includes("删除1、2、3，然后重新生成"));
      const correction = String(turns.at(-1)?.content).includes("等等，2不需要重新生成");
      const attachmentPreserved = original.length === 1 && String(original[0].content).includes("简历 ID：");
      if (original.length !== 1 || !correction || !attachmentPreserved) {
        response.writeHead(400, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: { message: "Original user request or latest correction missing/duplicated" } }));
        return;
      }
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(`data: ${JSON.stringify({ id: "steering-proof", object: "chat.completion.chunk", model: payload.model, choices: [{ index: 0, delta: { role: "assistant", content: "已理解：删除1、2、3，只重新生成1和3；删除仍需界面确认。" }, finish_reason: null }] })}\n\n`);
      response.write(`data: ${JSON.stringify({ id: "steering-proof", object: "chat.completion.chunk", model: payload.model, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
      response.end("data: [DONE]\n\n"); return;
    }
    if (["approval-integration-probe", "deletion-proposal-integration-probe"].includes(payload.model) && isAgentRequest) {
      const deletionProbe = payload.model === "deletion-proposal-integration-probe";
      const memoryId = payload.messages?.filter(message => message.role === "user").at(-1)?.content?.match(/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/i)?.[0];
      const delta = hasToolResult
        ? { role: "assistant", content: "Permission probe finished." }
        : { role: "assistant", tool_calls: [{ index: 0, id: "approved-memory-probe", type: "function",
          function: { name: deletionProbe ? "delete_memory" : "add_memory", arguments: JSON.stringify(deletionProbe ? { memoryId } : { memory: "Synthetic approved preference" }) } }] };
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache" });
      for (const [chunk, finish] of [[delta, null], [{}, hasToolResult ? "stop" : "tool_calls"]]) {
        response.write(`data: ${JSON.stringify({ id: "approval-probe", object: "chat.completion.chunk", model: payload.model,
          choices: [{ index: 0, delta: chunk, finish_reason: finish }] })}\n\n`);
      }
      response.end("data: [DONE]\n\n");
      return;
    }
      if (["agent-silence-probe", "agent-cancel-probe"].includes(payload.model) && isAgentRequest) {
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache" });
        response.flushHeaders();
        response.write(": connection open, no model output\n\n");
        const late = setTimeout(() => {
          if (response.destroyed) return;
          response.write(`data: ${JSON.stringify({ id: "late-tool-probe", object: "chat.completion.chunk", model: payload.model,
            choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id: "late-add-memory", type: "function", function: { name: "add_memory", arguments: JSON.stringify({ memory: "Late callback must never write memory" }) } }] }, finish_reason: null }] })}\n\n`);
          response.write(`data: ${JSON.stringify({ id: "late-tool-probe", object: "chat.completion.chunk", model: payload.model, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }] })}\n\n`);
          response.end("data: [DONE]\n\n");
        }, 8000);
        response.on("close", () => clearTimeout(late));
        return;
      }
      if (payload.model === "queue-failure-probe" && isAgentRequest
          && payload.messages?.filter((message) => message.role === "user").at(-1)?.content?.includes("Queue failure marker")) {
        response.writeHead(503, { "content-type": "application/json; charset=utf-8" });
        response.end(JSON.stringify({ error: { message: "Synthetic queued upstream failure" } }));
        return;
      }
    if (payload.model === "gpt-5-depth-probe" && isAgentRequest
      && !["low", "medium", "high"].includes(payload.reasoning_effort)) {
      response.writeHead(400, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ error: { message: "reasoning_effort was not transmitted" } }));
      return;
    }
    const toolResults = payload.messages?.filter((message) => message.role === "tool") ?? [];
    if (payload.model === "knowledge-module-probe" && isAgentRequest && !probeTool) {
      const available = new Set(payload.tools.map(tool => tool.function?.name));
      if (!["list_knowledge_documents", "search_knowledge", "research_knowledge_from_web", "delete_knowledge_document"]
        .every(name => available.has(name))) {
        response.writeHead(400);
        response.end(JSON.stringify({ error: { message: "Knowledge adapter schema missing" } }));
        return;
      }
      const message = toolResults.length === 0
        ? { role: "assistant", content: null, tool_calls: [{ id: "knowledge-list", type: "function",
          function: { name: "list_knowledge_documents", arguments: "{}" } }] }
        : { role: "assistant", content: `Owned knowledge result: ${toolResults.at(-1).content}` };
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ model: payload.model, choices: [{ message }] }));
      return;
    }
    if (payload.model === "stream-probe-model" && isAgentRequest) {
      console.log(`stream-probe tools=${payload.tools.map((tool) => tool.function?.name).join(",")}`);
    }
    if (payload.model === "calendar-tool-probe" && isAgentRequest) {
      console.log(`calendar-tool-probe toolResults=${toolResults.length} ${JSON.stringify(toolResults)}`);
      if (toolResults.length === 0) {
        response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
        response.end(JSON.stringify({
          model: payload.model,
          choices: [{ message: { role: "assistant", content: null, tool_calls: [{
            id: "calendar-create-1",
            type: "function",
            function: {
              name: "create_calendar_task",
              arguments: JSON.stringify({
                title: "字节面试",
                description: "视频面试：AI应用开发实习生-财经业务（抖音集团财经业务）；提前准备项目案例与系统设计。",
                dueAt: "2026-09-10T14:00:00+08:00",
                priority: "HIGH",
              }),
            },
          }] } }],
        }));
        return;
      }
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ model: payload.model, choices: [{ message: { role: "assistant", content: "已把面试安排到 9 月 10 日 14:00。" } }] }));
      return;
    }
    if (payload.model === "learning-proposal-probe" && isAgentRequest) {
      const message = toolResults.length === 0
        ? { role: "assistant", content: null, tool_calls: [{
          id: "learning-proposal-1", type: "function",
          function: { name: "create_learning_plan", arguments: JSON.stringify({
            concept: "Redis 持久化", reason: "面试中没有说明 AOF 的恢复取舍",
            scheduledAt: null, sourceType: "AGENT", sourceId: null,
          }) },
        }] }
        : { role: "assistant", content: "建议安排 Redis 持久化学习；请点击卡片确认后再生成。" };
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ model: payload.model, choices: [{ message }] }));
      return;
    }
    if (payload.model === "tool-serialization-probe" && isAgentRequest && toolResults.length < 2) {
      const toolName = toolResults.length === 0 ? "list_resumes" : "list_interviews";
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({
        model: payload.model,
        choices: [{ message: { role: "assistant", content: null, tool_calls: [{ id: `serialization-${toolResults.length + 1}`, type: "function", function: { name: toolName, arguments: "{}" } }] } }],
      }));
      return;
    }
    if (payload.model === "retry-probe-model" && isAgentRequest) {
      const attempt = (transientAttempts.get(payload.model) ?? 0) + 1;
      transientAttempts.set(payload.model, attempt);
      console.log(`retry-probe attempt ${attempt}`);
      if (attempt <= 3) {
        response.writeHead(503, { "content-type": "application/json; charset=utf-8" });
        response.end(JSON.stringify({ error: { message: "temporary upstream outage" } }));
        return;
      }
    }
    if (payload.model === "replay-ui-recovery-probe" && body.includes("面试复盘教练")) {
      const attempt = (transientAttempts.get(payload.model) ?? 0) + 1;
      transientAttempts.set(payload.model, attempt);
      if (attempt === 1) {
        response.writeHead(503, { "content-type": "application/json; charset=utf-8" });
        response.end(JSON.stringify({ error: { message: "temporary replay outage" } }));
        return;
      }
    }
    if (probeTool && !hasToolResult && !request.url.endsWith("/v1/messages")) {
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({
        model: payload.model,
        choices: [{ message: { role: "assistant", content: null, tool_calls: [{ id: "probe-call-1", type: "function", function: { name: "tool_calling_probe", arguments: "{}" } }] } }],
      }));
      return;
    }
    const content = JSON.stringify(completionFor(payload));
    if (payload.stream && !request.url.endsWith("/v1/messages")) {
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "keep-alive",
      });
      const chunks = content.match(/.{1,4}/gu) ?? [content];
      const events = chunks.map((text, index) => `data: ${JSON.stringify({
        id: "stream-test-1", object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: payload.model,
        choices: [{ index: 0, delta: index === 0 ? { role: "assistant", content: text } : { content: text }, finish_reason: null }],
      })}\n\n`);
      events.push(`data: ${JSON.stringify({ id: "stream-test-1", object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: payload.model, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
      events.push("data: [DONE]\n\n");
      if (payload.model === "stream-probe-model") {
        let index = 0;
        const timer = setInterval(() => {
          if (index < events.length - 1) response.write(events[index++]);
          else { clearInterval(timer); response.end(events[index]); }
        }, 120);
      } else {
        events.slice(0, -1).forEach((event) => response.write(event));
        response.end(events.at(-1));
      }
      return;
    }
    const result = request.url.endsWith("/v1/messages")
      ? { model: payload.model, content: [{ type: "text", text: content }] }
      : { model: payload.model, choices: [{ message: { role: "assistant", content } }] };
    const respond = () => {
      if (response.destroyed) return;
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify(result));
    };
    if (concurrentLearning) {
      pendingLearningResponses.push(respond);
      if (pendingLearningResponses.length === 2) releaseLearningResponses();
      else learningFallbackTimer = setTimeout(releaseLearningResponses, 5000);
    } else if (payload.model === "graph-live-probe") setTimeout(respond, 1500);
    else if (restartAssessment) setTimeout(respond,restartRequests===1 ? 45000 : 1500);
    else if (delayedReplay || payload.model === "async-evaluation-probe") setTimeout(respond, 1500);
    else respond();
  });
}).listen(8090, "0.0.0.0");

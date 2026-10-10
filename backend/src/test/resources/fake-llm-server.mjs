import http from "node:http";

const analysis = {
  overallScore: 82,
  categoryScores: { content: 84, impact: 76, clarity: 85, roleFit: 83 },
  summary: "技术栈聚焦，项目经历清晰；补充量化结果后会更有说服力。",
  education: ["计算机相关专业"],
  experience: ["Java 后端开发经历"],
  projects: ["Spring Boot 服务项目"],
  skills: ["Java", "Spring Boot", "PostgreSQL"],
  strengths: ["后端技术栈聚焦", "有可验证的项目经验"],
  weaknesses: ["缺少量化业务结果"],
  possibleTargetRoles: ["Java 后端工程师"],
  recommendations: ["为项目补充性能、规模或业务结果数据", "用动词开头重写核心职责"],
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
    explanation: "RDB 定期保存快照，AOF 记录写命令；需要结合恢复目标和性能预算选择。",
    example: "对容忍少量数据丢失的缓存可偏重 RDB。",
    pitfalls: ["不要把 AOF 重写当作立即持久化"],
  },
  exercises: [
    { question: "哪项描述 AOF？", type: "MULTIPLE_CHOICE", options: ["记录写命令", "只保存快照", "不支持重写"], rubric: ["记录写命令"] },
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
  if (payload.model === "recap-recovery-probe" && input.includes("面试复盘编辑")) {
    const attempt = (transientAttempts.get(payload.model) ?? 0) + 1;
    transientAttempts.set(payload.model, attempt);
    // A structurally valid but unusable first answer exercises the queue's
    // FAILED state and user-initiated retry, not the gateway's HTTP retries.
    if (attempt === 1) return { ...recap, questions: [] };
  }
  if (input.includes("面试复盘编辑")) return recap;
  if (input.includes("面试复盘教练")) return replay;
  if (input.includes("严谨的中文技术导师")) return lesson;
  if (input.includes("严格但有建设性的中文技术教练")) {
    const improved = input.includes("二轮修订");
    return {
      score: improved ? 85 : 65,
      feedback: improved ? "已经说明关键取舍。" : "还需要解释恢复目标。",
      strengths: ["能区分快照与日志"],
      gaps: improved ? [] : ["缺少恢复目标"],
      questionFeedback: [{ question: "如何选择 RDB 与 AOF？", feedback: "结合恢复目标说明即可。" }],
      nextFocus: improved ? "保持练习" : "恢复时间与数据丢失窗口",
      nextExercises: improved ? [] : lesson.exercises,
    };
  }
  if (input.includes("简历分析器")) return analysis;
  if (input.includes("技术面试官")) return questions;
  if (input.includes("面试评价官")) return evaluation;
  return { ok: true };
}

http.createServer((request, response) => {
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
    const delayedReplay = payload.model === "delayed-replay-model" && body.includes("面试复盘教练");
    const concurrentLearning = payload.model === "concurrent-learning-model"
      && body.includes("严格但有建设性的中文技术教练");
    if (delayedReplay) delayedReplayRequests++;
    const probeTool = payload.tools?.find((tool) => tool.function?.name === "tool_calling_probe");
    const hasToolResult = payload.messages?.some((message) => message.role === "tool");
    const isAgentRequest = payload.tools?.some((tool) => tool.function?.name === "list_resumes");
    const toolResults = payload.messages?.filter((message) => message.role === "tool") ?? [];
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
      response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
      response.end(JSON.stringify(result));
    };
    if (concurrentLearning) {
      pendingLearningResponses.push(respond);
      if (pendingLearningResponses.length === 2) releaseLearningResponses();
      else learningFallbackTimer = setTimeout(releaseLearningResponses, 5000);
    } else if (delayedReplay) setTimeout(respond, 1500);
    else respond();
  });
}).listen(8090, "0.0.0.0");

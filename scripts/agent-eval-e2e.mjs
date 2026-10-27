#!/usr/bin/env node
/**
 * End-to-end business-fixture evaluation (disposable stack, fake model, no cost).
 *
 * Unlike the synthetic-trace contract demo, this driver creates a fresh user per
 * case (real data isolation, not just a new conversation), seeds initial business
 * state through the public REST API, drives the real agent runtime against the
 * deterministic fake LLM, handles write approvals, reads the business state back,
 * and only then scores both the trace and the resulting database state.
 *
 * Requires an isolated disposable stack:
 *   ARDOR_ACCEPTANCE_DISPOSABLE_DB=true
 *   ARDOR_EVAL_E2E=true
 *   ARDOR_BASE_URL (default http://127.0.0.1:8080)
 *   ARDOR_ACCEPTANCE_LLM_BASE (default http://127.0.0.1:8090)
 */
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { scoreCase } from "./agent-eval-assertions.mjs";
import { buildReport } from "./agent-eval-report.mjs";
import { SseParser } from "./agent-eval-sse.mjs";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") {
  throw new Error("E2E evaluation requires ARDOR_ACCEPTANCE_DISPOSABLE_DB=true");
}
if (process.env.ARDOR_EVAL_E2E !== "true") {
  throw new Error("Set ARDOR_EVAL_E2E=true to run the end-to-end business evaluation.");
}

const base = (process.env.ARDOR_BASE_URL ?? "http://127.0.0.1:8080").replace(/\/+$/, "");
const modelBase = (process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090").replace(/\/+$/, "");
const here = path.dirname(fileURLToPath(import.meta.url));
const dataset = JSON.parse(fs.readFileSync(path.join(here, "agent-eval-cases.json"), "utf8"));
const casesById = Object.fromEntries(dataset.cases.map((item) => [item.id, item]));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Client {
  cookies = new Map();

  capture(response) {
    for (const line of response.headers.getSetCookie?.() ?? []) {
      const pair = line.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }

  cookieHeader() {
    return [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
  }

  async csrf() {
    const response = await fetch(`${base}/api/auth/csrf`, { headers: { Cookie: this.cookieHeader() } });
    if (!response.ok) throw new Error(`CSRF failed: HTTP ${response.status}`);
    this.capture(response);
    return response.json();
  }

  async raw(requestPath, { method = "GET", body, accept = "application/json" } = {}) {
    const headers = { Accept: accept };
    if (body !== undefined || !["GET", "HEAD"].includes(method)) {
      const token = await this.csrf();
      headers[token.headerName] = token.token;
      if (body !== undefined) headers["Content-Type"] = "application/json";
    }
    headers.Cookie = this.cookieHeader();
    const response = await fetch(`${base}${requestPath}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    });
    this.capture(response);
    return response;
  }

  async json(requestPath, { method = "GET", body, expected = 200 } = {}) {
    const response = await this.raw(requestPath, { method, body });
    const text = await response.text();
    if (response.status !== expected) {
      throw new Error(`${method} ${requestPath}: expected ${expected}, got ${response.status}: ${text.slice(0, 300)}`);
    }
    return text ? JSON.parse(text) : null;
  }
}

async function registerUser(label) {
  const client = new Client();
  const suffix = randomUUID();
  const compact = label.replace(/[^a-z0-9]/gi, "").slice(0, 16);
  const token = suffix.replace(/-/g, "").slice(0, 12);
  await client.json("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `eval-${compact}-${token}@ardor.invalid`, password: `Eval-${suffix}!`, displayName: `Eval ${label}` },
  });
  await client.json("/api/settings/llm", {
    method: "PUT",
    body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase, model: "eval-script-probe", apiKey: "disposable-fake-model-key" },
  });
  return client;
}

const listTasks = (client) => client.json("/api/calendar/tasks");
const createTask = (client, title, dueAt) => client.json("/api/calendar/tasks", {
  method: "POST", expected: 201, body: { title, description: "synthetic eval fixture", dueAt, priority: "MEDIUM", jobOpportunityId: null },
});
const deleteTask = (client, taskId) => client.json(`/api/calendar/tasks/${taskId}`, { method: "DELETE", expected: 204 });

function messageFor(plan) {
  const directive = plan.script?.length ? `\n\n<!-- eval-tools:${JSON.stringify(plan.script)} -->` : "";
  const answer = plan.answer ? `\n<!-- eval-answer:${plan.answer} -->` : "";
  return `${plan.prompt}${directive}${answer}`;
}

async function drive(client, plan, timeoutMs = 60_000) {
  const requestId = randomUUID();
  const events = [];
  let streamError = null;
  let streamDone = false;
  const stream = (async () => {
    try {
      const response = await client.raw("/api/agent/messages/stream", {
        method: "POST", accept: "text/event-stream",
        body: { message: messageFor(plan), requestId, reasoningDepth: "AUTO", contextReferences: [] },
      });
      if (!response.ok) throw new Error(`HTTP_${response.status}: ${(await response.text()).slice(0, 200)}`);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = new SseParser((payload) => {
        try { events.push(JSON.parse(payload)); } catch { throw new Error("INVALID_STREAM_EVENT"); }
      });
      while (true) {
        const { value, done } = await reader.read();
        parser.push(decoder.decode(value, { stream: !done }), done);
        if (done) break;
      }
    } catch (error) {
      streamError = error;
    } finally {
      streamDone = true;
    }
  })();

  const approvals = (async () => {
    const deadline = Date.now() + timeoutMs;
    while (!streamDone && Date.now() < deadline) {
      try {
        const pending = await client.json(`/api/agent/runs/${requestId}/approvals`);
        for (const approval of pending.filter((item) => item.status === "PENDING")) {
          await client.json(`/api/agent/runs/${requestId}/approvals/${approval.id}`, {
            method: "POST", expected: 204, body: { allow: plan.approval === "allow" },
          });
        }
      } catch { /* run may not be registered yet; retry until the stream settles */ }
      await sleep(250);
    }
  })();

  await stream;
  await approvals;
  const done = events.find((event) => event.type === "done");
  const errorEvent = events.find((event) => event.type === "error");
  return { requestId, answer: done?.content, streamError, errorCode: errorEvent?.errorCode ?? null };
}

async function trace(client, requestId) {
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      const response = await client.raw(`/api/agent/runs/${requestId}/trace`);
      if (response.ok) {
        const body = await response.json();
        if (Array.isArray(body.spans) && body.spans.length > 0) return body;
      }
    } catch { /* trace may not be persisted yet */ }
    await sleep(200);
  }
  return null;
}

const future = (days) => new Date(Date.now() + days * 86_400_000).toISOString();

// Each plan maps a dataset case to a real business fixture and a read-back assertion.
const PLANS = {
  "read-calendar": {
    seed: async (client) => { await createTask(client, "已有日程 A", future(1)); await createTask(client, "已有日程 B", future(2)); },
    script: [{ name: "list_calendar_tasks", arguments: {} }],
    answer: "这是你本周末前后的日程。",
    approval: "deny",
    assert: (before, after) => before.length === 2 && after.length === 2 ? [] : [`expected 2 tasks before and after, got ${before.length}/${after.length}`],
  },
  "create-calendar-approved": {
    seed: async () => {},
    script: [{ name: "create_calendar_task", arguments: { title: "简历复盘", description: "", dueAt: future(1), priority: "MEDIUM", jobOpportunityId: "" } }],
    answer: "已创建『简历复盘』。",
    approval: "allow",
    assert: (_before, after) => after.length === 1 && after[0].title.includes("简历复盘")
      ? [] : [`expected one created task titled 简历复盘, got ${JSON.stringify(after.map((task) => task.title))}`],
  },
  "modify-calendar-approved": {
    seed: async (client) => {
      const task = await createTask(client, "组会", future(1));
      return { taskId: task.id, originalDueAt: task.dueAt };
    },
    script: (state) => [
      { name: "list_calendar_tasks", arguments: {} },
      { name: "update_calendar_task", arguments: {
        taskId: state.taskId, title: "组会", description: "synthetic eval fixture", dueAt: future(3), priority: "MEDIUM", status: "TODO" } },
    ],
    answer: "已把组会改到新的时间。",
    approval: "allow",
    assert: (before, after, state) => {
      const task = after.find((item) => item.id === state.taskId);
      if (!task) return ["seeded task disappeared after update"];
      if (task.title !== "组会") return [`title changed unexpectedly: ${task.title}`];
      return Date.parse(task.dueAt) !== Date.parse(state.originalDueAt) ? [] : ["task dueAt did not change"];
    },
  },
  "delete-calendar-proposes-only": {
    seed: async (client) => { const task = await createTask(client, "组会", future(1)); return { taskId: task.id }; },
    script: (state) => [{ name: "delete_calendar_task", arguments: { taskId: state.taskId } }],
    answer: "删除需要你在界面确认。",
    approval: "deny",
    assert: (_before, after, state) => after.some((task) => task.id === state.taskId) ? [] : ["proposal-only delete removed the task"],
  },
  "ambiguous-create-clarifies": {
    seed: async () => {},
    script: [],
    answer: "请告诉我具体的时间和标题，我再帮你创建。",
    approval: "deny",
    assert: (_before, after) => after.length === 0 ? [] : [`clarification created ${after.length} task(s)`],
  },
  "invalid-create-params": {
    seed: async () => {},
    script: [{ name: "create_calendar_task", arguments: { title: "乱码 ??@@##", description: "", dueAt: "not-a-date", priority: "MEDIUM", jobOpportunityId: "" } }],
    answer: "参数不合法，未创建日程。",
    approval: "allow",
    assert: (_before, after) => after.length === 0 ? [] : [`invalid params still created ${after.length} task(s)`],
  },
  "read-only-no-write": {
    seed: async (client) => { await createTask(client, "已有日程 A", future(1)); await createTask(client, "已有日程 B", future(2)); },
    script: [{ name: "list_calendar_tasks", arguments: {} }],
    answer: "这是本周安排，我只做了读取。",
    approval: "deny",
    assert: (before, after) => before.length === 2 && after.length === 2 ? [] : [`read-only request changed task count ${before.length}->${after.length}`],
  },
  "foreign-resource-not-modified": {
    foreign: true,
    seed: async () => {},
    script: (state) => [{ name: "update_calendar_task", arguments: {
      taskId: state.foreignTaskId, title: "hijack", description: "", dueAt: future(2), priority: "HIGH", status: "TODO" } }],
    answer: "无法修改其他用户的日程。",
    approval: "allow",
    assert: (_before, after, state, foreignAfter) => {
      const failures = [];
      if (after.length !== 0) failures.push(`stranger created ${after.length} own task(s)`);
      const foreign = foreignAfter.find((task) => task.id === state.foreignTaskId);
      if (!foreign) failures.push("foreign task disappeared");
      else if (foreign.title !== "组会") failures.push(`foreign task was modified to ${foreign.title}`);
      return failures;
    },
  },
  "read-then-create-multistep": {
    seed: async (client) => { await createTask(client, "已有日程 A", future(1)); await createTask(client, "已有日程 B", future(2)); },
    script: [
      { name: "list_calendar_tasks", arguments: {} },
      { name: "create_calendar_task", arguments: { title: "项目评审", description: "", dueAt: future(4), priority: "MEDIUM", jobOpportunityId: "" } },
    ],
    answer: "看过现有日程后已创建『项目评审』。",
    approval: "allow",
    assert: (_before, after) => after.length === 3 && after.some((task) => task.title.includes("项目评审"))
      ? [] : [`expected 3 tasks including 项目评审, got ${JSON.stringify(after.map((task) => task.title))}`],
  },
};

function combinedResult(declaration, plan, outcome, before, after, foreignAfter) {
  const scored = scoreCase(declaration, outcome.trace, outcome.answer, outcome.environmentError
    ? { environmentError: true, errorKind: outcome.errorKind } : {});
  if (!outcome.environmentError) {
    const businessFailures = plan.assert(before, after, outcome.state, foreignAfter);
    if (businessFailures.length) {
      scored.failures.push(...businessFailures);
      scored.dimensions.taskSuccess = false;
      scored.errorKind = "BUSINESS_STATE";
    }
    scored.observed.business = { before: before.length, after: after.length, foreignAfter: foreignAfter?.length ?? null };
  }
  return scored;
}

const results = [];
for (const [id, plan] of Object.entries(PLANS)) {
  const declaration = casesById[id];
  if (!declaration) throw new Error(`dataset is missing case ${id}`);
  const client = await registerUser(id);
  const state = (await plan.seed(client, declaration)) ?? {};
  const before = await listTasks(client);
  let foreignAfter = null;
  if (plan.foreign) {
    const foreignClient = await registerUser(`${id}-foreign`);
    if (!state.foreignTaskId) {
      const foreignTask = await createTask(foreignClient, "组会", future(1));
      state.foreignTaskId = foreignTask.id;
    }
    plan.script = plan.script(state);
    const outcome = await drive(client, { ...declaration, ...plan, script: plan.script });
    const traceData = await trace(client, outcome.requestId);
    foreignAfter = await listTasks(foreignClient);
    const after = await listTasks(client);
    results.push(combinedResult(declaration, plan, {
      ...outcome, trace: traceData, environmentError: traceData == null,
      errorKind: traceData == null ? (outcome.errorCode ?? "NO_TRACE") : null, state,
    }, before, after, foreignAfter));
    continue;
  }
  const scripted = typeof plan.script === "function" ? plan.script(state) : plan.script;
  const outcome = await drive(client, { ...declaration, ...plan, script: scripted });
  const traceData = await trace(client, outcome.requestId);
  const after = await listTasks(client);
  results.push(combinedResult(declaration, plan, {
    ...outcome, trace: traceData, environmentError: traceData == null,
    errorKind: traceData == null ? (outcome.errorCode ?? "NO_TRACE") : null, state,
  }, before, after, foreignAfter));
  // Fresh disposable users isolate rows; clean up the created task to honour the fixture contract.
  if (declaration.fixture?.cleanup?.includes("delete") && after.length === 1) await deleteTask(client, after[0].id).catch(() => {});
}

let codeSha = "unknown";
try {
  const { execFileSync } = await import("node:child_process");
  codeSha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: here, encoding: "utf8" }).trim();
} catch { /* keep unknown */ }

const report = buildReport({
  generatedAt: new Date().toISOString(), codeSha,
  dataset: { ...dataset, cases: Object.keys(PLANS).map((id) => casesById[id]) },
  toolSchema: null, model: "fake/eval-script-probe",
  effectiveParams: { reasoningDepth: "AUTO", isolation: "fresh user per case", driver: "eval-script-probe" },
  results,
});
report.scope = "e2e business subset (calendar create/modify/proposal/ownership/read-back)";
if (process.env.ARDOR_EVAL_REPORT) fs.writeFileSync(process.env.ARDOR_EVAL_REPORT, JSON.stringify(report, null, 2));

for (const item of report.cases) {
  console.log(`${item.status.padEnd(5)} ${item.id} kind=${item.errorKind ?? "-"} tasks=${item.observed?.business?.before ?? "?"}->${item.observed?.business?.after ?? "?"}`);
  for (const failure of item.failures) console.log(`      ${failure}`);
}
console.log(JSON.stringify(report.metrics));
if (report.cases.some((item) => item.status !== "PASS")) process.exitCode = 1;

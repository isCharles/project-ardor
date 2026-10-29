import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { syntheticResumePdf } from "./acceptance-fixtures.mjs";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200, raw = false, binary = false } = {}) {
    const headers = {};
    if (method !== "GET") { const csrf = await this.request("/api/auth/csrf"); headers[csrf.headerName] = csrf.token; }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    const form = body instanceof FormData;
    if (body && !form) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, { method, headers,
      body: body ? form ? body : JSON.stringify(body) : undefined, signal: AbortSignal.timeout(45000) });
    for (const line of response.headers.getSetCookie()) { const pair = line.split(";")[0], at = pair.indexOf("="); this.cookies.set(pair.slice(0, at), pair.slice(at + 1)); }
    if (binary) { assert.equal(response.status, expected, `${method} ${path}`); return Buffer.from(await response.arrayBuffer()); }
    const text = await response.text(); assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 180)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}
const owner = new Client(), foreign = new Client();
for (const client of [owner, foreign]) await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `resume-module-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "resume-module-probe", apiKey: "fake-only" } });
async function upload(client, filename) {
  const data = new FormData(); data.set("file", new Blob([syntheticResumePdf()], { type: "application/pdf" }), filename);
  return client.request("/api/resumes", { method: "POST", expected: 201, body: data });
}
const first = await upload(owner, "Owner resume fixture.pdf"), second = await upload(owner, "Owner second fixture.pdf");
const theirs = await upload(foreign, "Foreign resume fixture.pdf");
const fields = ["id", "originalFilename", "contentType", "sizeBytes", "checksumSha256", "parseStatus", "createdAt", "analysisId", "analysisStatus", "analysisError", "overallScore", "primary", "displayAnalysisId"].sort();
assert.deepEqual(Object.keys(first).sort(), fields);
assert.equal(first.parseStatus, "PARSED"); assert.ok(first.createdAt);
assert.equal(first.analysisId, null); assert.equal(first.analysisStatus, null);
assert.equal(first.primary, true, "the first upload becomes the primary resume");
assert.equal(first.displayAnalysisId, null);
await owner.request(`/api/resumes/${first.id}/analysis`, { method: "POST", expected: 202 });
let status;
for (let attempt = 0; attempt < 100; attempt++) {
  status = await owner.request(`/api/resumes/${first.id}/analysis/status`);
  if (status.status === "COMPLETED") break;
  if (status.status === "FAILED") throw new Error(status.errorMessage);
  await new Promise(resolve => setTimeout(resolve, 100));
}
assert.equal(status.status, "COMPLETED");
const report = await owner.request(`/api/resumes/${first.id}/analysis`);
assert.deepEqual(Object.keys(report).sort(), ["id", "resumeId", "analysis", "modelName", "promptVersion", "createdAt"].sort());
assert.equal((await owner.request(`/api/resumes/${first.id}/analysis?analysisId=${report.id}`)).id, report.id);
assert.equal((await owner.request(`/api/resumes/${first.id}/analysis/history`))[0].id, report.id);
await owner.request(`/api/resumes/${second.id}/analysis?analysisId=${report.id}`, { expected: 404 });
for (const path of [`/api/resumes/${first.id}/analysis?analysisId=${report.id}`, `/api/resumes/${first.id}/analysis/history`, `/api/resumes/${first.id}/file`]) {
  await foreign.request(path, { expected: 404 });
}
await foreign.request(`/api/resumes/${first.id}`, { method: "DELETE", expected: 404 });

// LaTeX workspace: revisions append, a stale base conflicts instead of overwriting,
// and a frozen revision cannot be rewritten by a later edit.
const project = await owner.request("/api/latex/projects", { method: "POST", expected: 201,
  body: { title: "Synthetic LaTeX workspace" } });
assert.equal(project.mainFile, "main.tex");
assert.equal(project.revisionNo, 1);
assert.ok(project.files["main.tex"].includes("documentclass"), "a new workspace starts from the template");
assert.equal(project.frozenRevision, null);
const projects = await owner.request("/api/latex/projects");
assert.ok(projects.some(item => item.id === project.projectId));
const edited = { ...project.files, "main.tex": `${project.files["main.tex"]}
% synthetic edit
` };
const secondRevision = await owner.request(`/api/latex/projects/${project.projectId}/revisions`, { method: "POST", expected: 201,
  body: { baseRevision: 1, files: edited, message: "synthetic edit" } });
assert.equal(secondRevision.revisionNo, 2);
await owner.request(`/api/latex/projects/${project.projectId}/revisions`, { method: "POST", expected: 409,
  body: { baseRevision: 1, files: edited, message: "stale base" } });
await owner.request(`/api/latex/projects/${project.projectId}/revisions`, { method: "POST", expected: 400,
  body: { baseRevision: 2, files: { ...edited, "../escape.tex": "x" }, message: "traversal" } });
assert.equal((await owner.request(`/api/latex/projects/${project.projectId}?revision=1`)).files["main.tex"], project.files["main.tex"],
  "an old revision is still readable after a newer one lands");
const history = await owner.request(`/api/latex/projects/${project.projectId}/revisions`);
assert.deepEqual(history.map(item => item.revisionNo), [2, 1]);
const frozen = await owner.request(`/api/latex/projects/${project.projectId}/freeze`, { method: "POST", expected: 200,
  body: { revisionNo: 2 } });
assert.equal(frozen.frozenRevision, 2);
assert.equal((await owner.request(`/api/latex/projects/${project.projectId}/freeze`, { method: "POST", expected: 200,
  body: { revisionNo: 2 } })).frozenRevision, 2, "freezing the same revision is idempotent");
await foreign.request(`/api/latex/projects/${project.projectId}`, { expected: 404 });
await foreign.request(`/api/latex/projects/${project.projectId}/revisions`, { expected: 404 });
await foreign.request(`/api/latex/projects/${project.projectId}/freeze`, { method: "POST", expected: 404, body: { revisionNo: 2 } });
await owner.request(`/api/resumes/${first.id}/display-analysis`, { method: "POST", expected: 204, body: { analysisId: report.id } });
await foreign.request(`/api/resumes/${first.id}/display-analysis`, { method: "POST", expected: 404, body: { analysisId: report.id } });
await owner.request(`/api/resumes/${second.id}/primary`, { method: "POST", expected: 204 });
await foreign.request(`/api/resumes/${first.id}/primary`, { method: "POST", expected: 404 });
const ownList = await owner.request("/api/resumes");
assert.equal(ownList.find(item => item.id === first.id).analysisId, report.id);
assert.equal(ownList.find(item => item.id === first.id).displayAnalysisId, report.id, "the chosen report is remembered");
assert.equal(ownList.find(item => item.id === first.id).primary, false, "only one resume is primary");
assert.equal(ownList.find(item => item.id === second.id).primary, true, "the promoted resume is primary");
await owner.request(`/api/resumes/${first.id}/display-analysis`, { method: "POST", expected: 204, body: {} });
const cleared = await owner.request("/api/resumes");
assert.equal(cleared.find(item => item.id === first.id).displayAnalysisId, null);
assert.ok(cleared.every(item => Object.keys(item).sort().join() === fields.join()));
assert.ok(!ownList.some(item => item.id === theirs.id));
async function turn(message) {
  const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const stream = await owner.request("/api/agent/messages/stream", { method: "POST", raw: true,
    body: { conversationId: conversation.id, requestId: randomUUID(), message, reasoningDepth: "AUTO" } });
  const events = stream.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => JSON.parse(line.slice(5).trim()));
  assert.equal(events.filter(event => event.type === "done").length, 1, stream.slice(-1200));
  assert.equal(events.filter(event => event.type === "error").length, 0);
  return events;
}
await turn("查询我的简历 resume-probe-list");
await turn(`查询这份简历的分析状态 ${first.id} resume-probe-status`);
await turn(`读取这份简历的分析 ${first.id} resume-probe-report`);
await owner.request("/api/agent/permissions/*", { method: "PUT", expected: 204, body: { level: 3 } });
await turn(`分析这份简历 ${first.id} resume-probe-analyze`);
const events = await turn(`删除这份简历 ${first.id} resume-probe-delete`);
const confirmation = events.find(event => event.type === "confirm")?.confirmation;
assert.equal(confirmation?.targetId, first.id);
assert.equal(confirmation?.label, first.originalFilename);
assert.equal(confirmation?.endpoint, `/api/resumes/${first.id}`);
assert.ok((await owner.request("/api/resumes")).some(item => item.id === first.id), "Agent may only propose deletion");
assert.equal((await owner.request(`/api/resumes/${first.id}/analysis`)).id, report.id);
await owner.request(confirmation.endpoint, { method: "DELETE", expected: 204 });
assert.ok(!(await owner.request("/api/resumes")).some(item => item.id === first.id));
assert.ok((await owner.request("/api/resumes")).some(item => item.id === second.id));
assert.ok((await foreign.request("/api/resumes")).some(item => item.id === theirs.id));
assert.deepEqual(await owner.request(`/api/resumes/${second.id}/file`, { binary: true }), Buffer.from(syntheticResumePdf()));
assert.deepEqual(await foreign.request(`/api/resumes/${theirs.id}/file`, { binary: true }), Buffer.from(syntheticResumePdf()));
await owner.request(`/api/resumes/${first.id}/file`, { expected: 404 });
console.log("Resume module: real upload/list/report/history/tool flow, stable JSON, parent/user isolation, proposal-only deletion and surviving source files passed.");

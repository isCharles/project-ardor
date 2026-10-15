import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

// This creates real users and records. Run only against an isolated, disposable stack.
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") {
  throw new Error("Golden Path acceptance requires ARDOR_ACCEPTANCE_DISPOSABLE_DB=true");
}

const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const modelBase = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function syntheticResumePdf() {
  const stream="BT /F1 12 Tf 40 740 Td (Java Spring Boot developer. Built a login service with PostgreSQL and Redis. Documented performance results and incident recovery.) Tj ET";
  const objects=["<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`];
  let pdf="%PDF-1.4\n";
  const offsets=[0];
  objects.forEach((object,index) => { offsets.push(Buffer.byteLength(pdf)); pdf+=`${index+1} 0 obj\n${object}\nendobj\n`; });
  const xref=Buffer.byteLength(pdf);
  pdf+=`xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(offset=>`${String(offset).padStart(10,"0")} 00000 n \n`).join("")}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return pdf;
}

class Client {
  cookies = new Map();

  captureCookies(response) {
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }

  async request(path, { method = "GET", body, expected = 200 } = {}) {
    const headers = { Accept: "application/json" };
    if (this.cookies.size) {
      headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    }
    if (body !== undefined || !["GET", "HEAD"].includes(method)) {
      if (body !== undefined && !(body instanceof FormData)) headers["Content-Type"] = "application/json";
      const csrf = await this.request("/api/auth/csrf");
      headers[csrf.headerName] = csrf.token;
      headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    }
    const response = await fetch(new URL(path, base), {
      method, headers, body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
    this.captureCookies(response);
    const text = await response.text();
    if (response.status !== expected) {
      throw new Error(`${method} ${path}: expected HTTP ${expected}, got ${response.status}: ${text.slice(0, 400)}`);
    }
    return text ? JSON.parse(text) : null;
  }
}

async function waitForRecap(client, jobId) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const jobs = await client.request("/api/interview-recaps/jobs");
    const job = jobs.find((item) => item.jobId === jobId);
    if (job?.status === "COMPLETED") return job.recapId;
    if (job?.status === "FAILED") throw new Error(`Recap worker failed: ${job.errorCode}`);
    await sleep(500);
  }
  throw new Error("Recap worker did not finish within 40 seconds");
}

async function waitForJobStatus(client, jobId, expectedStatus) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const jobs = await client.request("/api/interview-recaps/jobs");
    const job = jobs.find((item) => item.jobId === jobId);
    if (job?.status === expectedStatus) return job;
    if (job?.status === "FAILED" && expectedStatus !== "FAILED") {
      throw new Error(`Recap worker failed: ${job.errorCode}`);
    }
    await sleep(500);
  }
  throw new Error(`Recap job did not reach ${expectedStatus} within 40 seconds`);
}

async function waitForReplayRequest(previousCount) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const response = await fetch(new URL("/__probe/replay-started", modelBase));
    const probe = await response.json();
    if (probe.count > previousCount) return probe.count;
    await sleep(100);
  }
  throw new Error("Delayed replay request did not reach the fake model");
}

function calendarTask(tasks, actionPath) {
  const matches = tasks.filter((task) => task.actionPath === actionPath);
  assert.equal(matches.length, 1, `Expected one calendar task for ${actionPath}`);
  return matches[0];
}

async function waitForAiJob(client, id, status = "COMPLETED") {
  for (let attempt = 0; attempt < 100; attempt++) {
    const job = await client.request(`/api/ai-jobs/${id}`);
    assert.equal(Object.hasOwn(job, "payload"), false, "Job metadata must not expose submitted answers");
    if (job.status === status) return job;
    if (job.status === "FAILED" && status !== "FAILED") throw new Error(`AI job failed: ${job.errorCode}: ${job.errorMessage}`);
    await sleep(200);
  }
  throw new Error(`AI job did not reach ${status}`);
}

async function main() {
  const owner = new Client();
  const stranger = new Client();
  const suffix = process.env.ARDOR_ACCEPTANCE_UI_SUFFIX ?? randomUUID();
  const password = `Ardor-Acceptance-${suffix}!`;
  await owner.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-owner-${suffix}@ardor.invalid`, password, displayName: "Golden Path Owner" },
  });
  await stranger.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-stranger-${suffix}@ardor.invalid`, password, displayName: "Golden Path Stranger" },
  });
  await owner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "golden-path-model", apiKey: "disposable-fake-model-key" },
  });

  const submitted = await owner.request("/api/interview-recaps", {
    method: "POST", expected: 202,
    body: { content: "我在 Ardor Labs 的 Java 后端工程师面试里被问到 Redis 的 RDB 和 AOF 有什么区别。我只说 RDB 是快照，没能解释 AOF 的写命令日志、重写和恢复取舍。" },
  });
  const recapId = submitted.recapId ?? await waitForRecap(owner, submitted.jobId);
  const recap = await owner.request(`/api/interview-recaps/${recapId}`);
  assert.equal(recap.company, "Ardor Labs");
  const weak = recap.questions.find((question) => question.performance === "WEAK");
  assert.ok(weak?.id, "Expected a weak recap question");
  await stranger.request(`/api/interview-recaps/${recapId}`, { expected: 404 });
  await stranger.request(`/api/interview-replays/questions/${weak.id}`, { expected: 404 });
  await stranger.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 404,
    body: { requestId: randomUUID(), answer: "未经授权的回放回答" },
  });
  assert.equal((await owner.request(`/api/interview-replays/questions/${weak.id}`)).attempts.length, 0,
    "A stranger must not create an attempt on the owner's question");

  const replayRequestId = randomUUID();
  const replayAnswer = "RDB 保存快照，AOF 记录写命令；要按恢复目标和性能成本选择。";
  const replay = await owner.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 201, body: { requestId: replayRequestId, answer: replayAnswer },
  });
  assert.ok(replay.nextChallenge, "Replay should suggest a variation");
  const replayRetry = await owner.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 201, body: { requestId: replayRequestId, answer: replayAnswer },
  });
  assert.equal(replayRetry.id, replay.id, "Replay retry must not create a second attempt");

  const dueAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
  const retest = await owner.request(`/api/interview-replays/questions/${weak.id}/retest`, {
    method: "POST", expected: 201, body: { attemptId: replay.id, dueAt },
  });
  const retestRetry = await owner.request(`/api/interview-replays/questions/${weak.id}/retest`, {
    method: "POST", expected: 201, body: { attemptId: replay.id, dueAt },
  });
  assert.equal(retestRetry.id, retest.id, "Retest scheduling must not duplicate its task");
  await stranger.request(`/api/interview-replays/questions/${weak.id}/retest`, {
    method: "POST", expected: 404, body: { attemptId: replay.id, dueAt },
  });
  await stranger.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 404,
    body: { requestId: randomUUID(), answer: "未经授权的复测回答", retestTaskId: retest.id },
  });
  assert.equal((await owner.request(`/api/interview-replays/questions/${weak.id}`)).attempts.length, 1,
    "Cross-user retest attempts must not appear in the owner's history");
  assert.equal(calendarTask(await owner.request("/api/calendar/tasks"),
    `/app/replay?question=${weak.id}&retest=${retest.id}`).status, "TODO");

  const planRequestId = randomUUID();
  const planInput = { requestId: planRequestId, concept: "Redis 持久化",
    reason: "面试中没有说清 AOF 和恢复取舍", sourceType: "RECAP", sourceId: weak.id, scheduledAt: dueAt };
  const plan = await owner.request("/api/learning-plans", {
    method: "POST", expected: 201, body: planInput,
  });
  assert.equal(plan.sourceId, weak.id);
  const planRetry = await owner.request("/api/learning-plans", {
    method: "POST", expected: 201, body: planInput,
  });
  assert.equal(planRetry.id, plan.id, "Learning creation retry must reuse its plan");
  await stranger.request(`/api/learning-plans/${plan.id}`, { expected: 404 });
  await stranger.request("/api/learning-plans", {
    method: "POST", expected: 404, body: { concept: "Redis 持久化", sourceType: "RECAP", sourceId: weak.id },
  });

  let tasks = await owner.request("/api/calendar/tasks");
  const ownerLearningTask = calendarTask(tasks, `/app/learning?id=${plan.id}`);
  const ownerRetestTask = calendarTask(tasks, `/app/replay?question=${weak.id}&retest=${retest.id}`);
  assert.equal(ownerLearningTask.status, "TODO");
  assert.equal(ownerRetestTask.status, "TODO");
  await stranger.request(`/api/learning-plans/${plan.id}/begin`, {
    method: "POST", expected: 404,
  });
  await stranger.request(`/api/learning-plans/${plan.id}/attempts`, {
    method: "POST", expected: 404,
    body: { answers: plan.exercises.map(() => "未经授权的回答") },
  });
  await stranger.request(`/api/learning-plans/${plan.id}`, {
    method: "DELETE", expected: 404,
  });
  await stranger.request(`/api/calendar/tasks/${ownerLearningTask.id}`, {
    method: "PUT", expected: 404,
    body: { title: ownerLearningTask.title, description: ownerLearningTask.description,
      dueAt: ownerLearningTask.dueAt, priority: ownerLearningTask.priority, status: "COMPLETED" },
  });
  await stranger.request(`/api/calendar/tasks/${ownerLearningTask.id}`, {
    method: "DELETE", expected: 404,
  });
  await stranger.request(`/api/calendar/tasks/${ownerRetestTask.id}`, {
    method: "PUT", expected: 404,
    body: { title: ownerRetestTask.title, description: ownerRetestTask.description,
      dueAt: ownerRetestTask.dueAt, priority: ownerRetestTask.priority, status: "COMPLETED" },
  });
  await stranger.request(`/api/calendar/tasks/${ownerRetestTask.id}`, {
    method: "DELETE", expected: 404,
  });
  assert.equal((await owner.request(`/api/learning-plans/${plan.id}`)).status, "SCHEDULED");
  assert.equal(calendarTask(await owner.request("/api/calendar/tasks"),
    `/app/learning?id=${plan.id}`).status, "TODO");
  assert.equal(calendarTask(await owner.request("/api/calendar/tasks"),
    `/app/replay?question=${weak.id}&retest=${retest.id}`).status, "TODO");

  const first = await owner.request(`/api/learning-plans/${plan.id}/attempts`, {
    method: "POST", body: { answers: plan.exercises.map(() => "第一次回答：RDB 是快照。") },
  });
  assert.equal(first.status, "NEEDS_REVIEW");
  assert.equal(first.attemptCount, 1);
  assert.ok(first.nextReviewAt, "Weak practice should be rescheduled");
  const second = await owner.request(`/api/learning-plans/${plan.id}/attempts`, {
    method: "POST", body: { answers: first.exercises.map(() => "二轮修订：RDB 快照和 AOF 日志的恢复目标不同。") },
  });
  assert.equal(second.status, "COMPLETED");
  assert.equal(second.attemptCount, 2);

  const retestRequestId = randomUUID();
  const retestAnswer = "先设定可接受的数据丢失窗口，再决定快照和日志策略。";
  const retestResult = await owner.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 201,
    body: { requestId: retestRequestId, answer: retestAnswer, retestTaskId: retest.id },
  });
  assert.equal(retestResult.challengeText, retest.challenge);
  const retestResultRetry = await owner.request(`/api/interview-replays/questions/${weak.id}`, {
    method: "POST", expected: 201,
    body: { requestId: retestRequestId, answer: retestAnswer, retestTaskId: retest.id },
  });
  assert.equal(retestResultRetry.id, retestResult.id);

  tasks = await owner.request("/api/calendar/tasks");
  assert.equal(calendarTask(tasks, `/app/learning?id=${plan.id}`).status, "COMPLETED");
  assert.equal(calendarTask(tasks, `/app/replay?question=${weak.id}&retest=${retest.id}`).status, "COMPLETED");
  await stranger.request(`/api/interview-replays/questions/${weak.id}/retest`, { expected: 404 });

  // An Agent suggestion must survive as a user-visible proposal without writing a plan.
  await owner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "learning-proposal-probe", apiKey: "disposable-fake-model-key" },
  });
  const before = await owner.request("/api/learning-plans");
  const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const suggestion = await owner.request("/api/agent/messages", {
    method: "POST", body: { conversationId: conversation.id, requestId: randomUUID(),
      message: "根据我的面试弱项，建议安排 Redis 持久化学习。" },
  });
  const trace = JSON.parse(suggestion.runTrace);
  const proposal = trace.proposals.find((item) => item.kind === "learning_plan");
  assert.ok(proposal?.learningPlan?.requestId, "Agent must persist a click-to-confirm proposal");
  const restored = await owner.request(`/api/agent?conversationId=${conversation.id}`);
  const restoredTrace = JSON.parse(restored.messages.at(-1).runTrace);
  assert.equal(restoredTrace.proposals[0].learningPlan.requestId, proposal.learningPlan.requestId,
    "Proposal must survive a conversation reload");
  assert.equal((await owner.request("/api/learning-plans")).length, before.length,
    "Agent tool must not create learning plans or calendar tasks before confirmation");
  const statusPath = `/api/learning-plans/request-status?requestIds=${proposal.learningPlan.requestId}`;
  assert.equal((await owner.request(statusPath))[0].status, "MISSING");
  const confirmed = await owner.request("/api/learning-plans", {
    method: "POST", expected: 201, body: proposal.learningPlan,
  });
  const confirmedRetry = await owner.request("/api/learning-plans", {
    method: "POST", expected: 201, body: proposal.learningPlan,
  });
  assert.equal(confirmedRetry.id, confirmed.id, "Confirmation retry must not duplicate the plan");
  assert.equal((await owner.request(statusPath))[0].status, "CREATED");
  assert.equal((await stranger.request(statusPath))[0].status, "MISSING",
    "Request-status lookup must be scoped to the current user");
  calendarTask(await owner.request("/api/calendar/tasks"), `/app/learning?id=${confirmed.id}`);
  await owner.request(`/api/learning-plans/${confirmed.id}`, { method: "DELETE", expected: 204 });
  assert.equal((await owner.request(statusPath))[0].status, "DELETED",
    "A confirmed-then-deleted proposal must not reappear as pending");
  await owner.request("/api/learning-plans", {
    method: "POST", expected: 409, body: proposal.learningPlan,
  });
  await owner.request("/api/learning-plans", {
    method: "POST", expected: 400,
    body: { concept: "过期计划", scheduledAt: new Date(Date.now() - 60_000).toISOString() },
  });

  // Leave a second, distinct proposal pending for the on-demand browser run.
  // The browser must create this one through the visible confirmation button.
  const pendingSuggestion = await owner.request("/api/agent/messages", {
    method: "POST", body: { conversationId: conversation.id, requestId: randomUUID(),
      message: "再建议我安排一次 Redis 持久化学习，等我点确认。" },
  });
  const pendingProposal = JSON.parse(pendingSuggestion.runTrace).proposals.find(
    (item) => item.kind === "learning_plan");
  assert.ok(pendingProposal?.learningPlan?.requestId);
  assert.notEqual(pendingProposal.learningPlan.requestId, proposal.learningPlan.requestId);
  assert.equal((await owner.request(
    `/api/learning-plans/request-status?requestIds=${pendingProposal.learningPlan.requestId}`))[0].status,
  "MISSING", "The new proposal must remain uncreated until the browser confirms it");

  // The first model answer has no usable questions. The worker must fail the
  // job without persisting a partial recap; only its owner may retry it.
  const recoveryOwner = stranger;
  await recoveryOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "recap-recovery-probe", apiKey: "disposable-fake-model-key" },
  });
  const recoveryInput = "在另一次 Ardor Labs Java 后端面试中，面试官追问 Redis 持久化的恢复窗口。我只回答了 RDB 是快照，没说清 AOF 重写和取舍。";
  const recapsBeforeFailure = await recoveryOwner.request("/api/interview-recaps");
  const failedSubmission = await recoveryOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202, body: { content: recoveryInput },
  });
  const failedJob = await waitForJobStatus(recoveryOwner, failedSubmission.jobId, "FAILED");
  assert.equal(failedJob.recapId, null);
  assert.equal((await recoveryOwner.request("/api/interview-recaps")).length, recapsBeforeFailure.length,
    "An unusable model answer must not leave a partial recap");
  await owner.request(`/api/interview-recaps/jobs/${failedJob.jobId}/retry`, {
    method: "POST", expected: 404,
  });
  const retried = await recoveryOwner.request(`/api/interview-recaps/jobs/${failedJob.jobId}/retry`, {
    method: "POST",
  });
  assert.equal(retried.jobId, failedJob.jobId, "Manual retry must reuse the original job");
  assert.equal(retried.status, "QUEUED");
  const recovered = await waitForJobStatus(recoveryOwner, failedJob.jobId, "COMPLETED");
  assert.ok(recovered.recapId);
  const duplicateSubmission = await recoveryOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202, body: { content: recoveryInput },
  });
  assert.equal(duplicateSubmission.recapId, recovered.recapId,
    "Submitting the same material after recovery must reuse the completed recap");
  assert.equal((await recoveryOwner.request("/api/interview-recaps")).length, recapsBeforeFailure.length + 1);

  // Cancel a retest after its LLM request starts but before the answer returns.
  // The rejected completion must roll back the replay attempt and leave the
  // same request ID available for an intentional retry after re-opening.
  const recoveredRecap = await recoveryOwner.request(`/api/interview-recaps/${recovered.recapId}`);
  const recoveredWeak = recoveredRecap.questions.find((question) => question.performance === "WEAK");
  assert.ok(recoveredWeak?.id);
  const practice = await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`, {
    method: "POST", expected: 201,
    body: { requestId: randomUUID(), answer: "RDB 保存快照，AOF 记录写命令。" },
  });
  const raceRetest = await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}/retest`, {
    method: "POST", expected: 201,
    body: { attemptId: practice.id, dueAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString() },
  });
  const raceTask = calendarTask(await recoveryOwner.request("/api/calendar/tasks"),
    `/app/replay?question=${recoveredWeak.id}&retest=${raceRetest.id}`);
  const beforeRace = await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`);
  await recoveryOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "delayed-replay-model", apiKey: "disposable-fake-model-key" },
  });
  const probe = await (await fetch(new URL("/__probe/replay-started", modelBase))).json();
  const raceRequest = { requestId: randomUUID(), answer: "我会先确定数据丢失窗口，再组合 RDB 和 AOF。",
    retestTaskId: raceRetest.id };
  const inFlight = recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`, {
    method: "POST", expected: 400, body: raceRequest,
  });
  await waitForReplayRequest(probe.count);
  await recoveryOwner.request(`/api/calendar/tasks/${raceTask.id}`, {
    method: "PUT", body: { title: raceTask.title, description: raceTask.description,
      dueAt: raceTask.dueAt, priority: raceTask.priority, status: "CANCELLED" },
  });
  await inFlight;
  assert.equal((await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`)).attempts.length,
    beforeRace.attempts.length, "Canceled retest must not leave an orphan replay attempt");
  await recoveryOwner.request(`/api/calendar/tasks/${raceTask.id}`, {
    method: "PUT", body: { title: raceTask.title, description: raceTask.description,
      dueAt: raceTask.dueAt, priority: raceTask.priority, status: "TODO" },
  });
  const replayAfterReopen = await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`, {
    method: "POST", expected: 201, body: raceRequest,
  });
  assert.equal(replayAfterReopen.requestId, raceRequest.requestId);
  assert.equal((await recoveryOwner.request(`/api/interview-replays/questions/${recoveredWeak.id}`)).attempts.length,
    beforeRace.attempts.length + 1);
  assert.equal(calendarTask(await recoveryOwner.request("/api/calendar/tasks"),
    `/app/replay?question=${recoveredWeak.id}&retest=${raceRetest.id}`).status, "COMPLETED");

  // Both evaluations read the same exercise round before the fake model
  // releases either response. Only one may commit feedback and calendar state.
  await recoveryOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "golden-path-model", apiKey: "disposable-fake-model-key" },
  });
  const concurrentPlan = await recoveryOwner.request("/api/learning-plans", {
    method: "POST", expected: 201,
    body: { concept: "Redis 持久化", sourceType: "RECAP", sourceId: recoveredWeak.id },
  });
  await recoveryOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "concurrent-learning-model", apiKey: "disposable-fake-model-key" },
  });
  const concurrentAnswers = { answers: concurrentPlan.exercises.map(() => "第一次回答：RDB 是快照。") };
  const submissions = await Promise.allSettled([1, 2].map(() => recoveryOwner.request(
    `/api/learning-plans/${concurrentPlan.id}/attempts`, { method: "POST", body: concurrentAnswers })));
  assert.equal(submissions.filter((item) => item.status === "fulfilled").length, 1,
    "Exactly one concurrent learning attempt should commit");
  const rejected = submissions.find((item) => item.status === "rejected");
  assert.match(String(rejected?.reason), /got 409:/,
    "The stale evaluation should ask for a refresh instead of overwriting feedback");
  const afterConcurrent = await recoveryOwner.request(`/api/learning-plans/${concurrentPlan.id}`);
  assert.equal(afterConcurrent.attemptCount, 1);
  assert.equal(calendarTask(await recoveryOwner.request("/api/calendar/tasks"),
    `/app/learning?id=${concurrentPlan.id}`).status, "TODO");

  // Leave a separate user's first recap job failed for the on-demand browser
  // test. No real user data or existing account is touched by this fixture.
  const uiRecoveryOwner = new Client();
  await uiRecoveryOwner.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-ui-recovery-${suffix}@ardor.invalid`, password,
      displayName: "Golden Path Recovery" },
  });
  await uiRecoveryOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "recap-ui-recovery-probe", apiKey: "disposable-fake-model-key" },
  });
  const uiFailedSubmission = await uiRecoveryOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202,
    body: { content: "我在 Ardor Labs 的 Java 后端面试被追问 Redis 持久化。我只回答了 RDB 是快照，没有解释 AOF 重写和恢复时的数据丢失窗口。" },
  });
  await waitForJobStatus(uiRecoveryOwner, uiFailedSubmission.jobId, "FAILED");
  assert.equal((await uiRecoveryOwner.request("/api/interview-recaps")).length, 0,
    "A failed recap must not expose partial notes to the browser user");

  // A third browser session fills metadata the model correctly left unknown.
  const metadataOwner = new Client();
  await metadataOwner.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-metadata-${suffix}@ardor.invalid`, password,
      displayName: "Golden Path Metadata" },
  });
  await metadataOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "recap-metadata-probe", apiKey: "disposable-fake-model-key" },
  });
  const metadataSubmission = await metadataOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202,
    body: { content: "在一次技术面试中，面试官问到 Redis 的 RDB 和 AOF 有什么区别。我只说了 RDB 是快照，遗漏了 AOF 重写与恢复取舍。" },
  });
  const metadataRecapId = metadataSubmission.recapId
    ?? await waitForRecap(metadataOwner, metadataSubmission.jobId);
  const incompleteRecap = await metadataOwner.request(`/api/interview-recaps/${metadataRecapId}`);
  assert.equal(incompleteRecap.company, null);
  assert.equal(incompleteRecap.targetRole, null);
  assert.equal(incompleteRecap.occurredAt, null);

  // Keep a clean weak question for the browser to complete the entire replay
  // and calendar retest flow through user-visible controls.
  const uiReplayOwner = new Client();
  await uiReplayOwner.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-ui-replay-${suffix}@ardor.invalid`, password,
      displayName: "Golden Path Replay" },
  });
  await uiReplayOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "replay-ui-recovery-probe", apiKey: "disposable-fake-model-key" },
  });
  const uiReplaySubmission = await uiReplayOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202,
    body: { content: "我在 Ardor Labs 的 Java 后端面试被问到 Redis 的 RDB 和 AOF 区别。我只说 RDB 是快照，没能解释 AOF 写命令、重写与恢复取舍。" },
  });
  const uiReplayRecapId = uiReplaySubmission.recapId
    ?? await waitForRecap(uiReplayOwner, uiReplaySubmission.jobId);
  const uiReplayRecap = await uiReplayOwner.request(`/api/interview-recaps/${uiReplayRecapId}`);
  assert.ok(uiReplayRecap.questions.some((question) => question.performance === "WEAK"),
    "Browser replay fixture needs a weak interview question");

  const depthOwner = new Client();
  await depthOwner.request("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `golden-depth-${suffix}@ardor.invalid`, password, displayName: "Golden Path Depth" },
  });
  await depthOwner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "gpt-5-depth-probe", apiKey: "disposable-fake-model-key" },
  });
  const depthConversation = await depthOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  await depthOwner.request("/api/agent/messages", {
    method: "POST", body: { conversationId: depthConversation.id, message: "帮我规划 Redis 面试学习", reasoningDepth: "DEEP" },
  });
  let titled = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    const conversations = await depthOwner.request("/api/agent/conversations");
    if (conversations.find((item) => item.id === depthConversation.id)?.title === "Redis 学习规划") { titled = true; break; }
    await sleep(200);
  }
  assert.ok(titled, "A completed turn must get an automatic summary title");
  await depthOwner.request(`/api/agent/conversations/${depthConversation.id}`, {
    method: "PATCH", body: { title: "我的手动标题" },
  });
  await depthOwner.request("/api/agent/messages", {
    method: "POST", body: { conversationId: depthConversation.id, message: "继续这次学习规划", reasoningDepth: "QUICK" },
  });
  await sleep(500);
  assert.equal((await depthOwner.request("/api/agent/conversations"))[0].title, "我的手动标题");
  const asyncOwner = new Client();
  // Durable queue: exact retry identity, owner isolation and ordered exchanges.
  const queuedFirst = { conversationId: depthConversation.id, requestId: randomUUID(), message: "Queue probe one", reasoningDepth: "QUICK" };
  const queuedSecond = { conversationId: depthConversation.id, requestId: randomUUID(), message: "Queue probe two", reasoningDepth: "DEEP" };
  const firstAccepted = await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: queuedFirst });
  assert.equal(firstAccepted.id, queuedFirst.requestId);
  assert.equal((await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: queuedFirst })).id, queuedFirst.requestId);
  await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: queuedSecond });
  await owner.request(`/api/agent/queue?conversationId=${depthConversation.id}`, { expected: 404 });
  await owner.request(`/api/agent/queue/${queuedFirst.requestId}`, { method: "DELETE", expected: 404 });
  let queuedComplete = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queue = await depthOwner.request(`/api/agent/queue?conversationId=${depthConversation.id}`);
    if (queue.filter((item) => [queuedFirst.requestId, queuedSecond.requestId].includes(item.id) && item.status === "COMPLETED").length === 2) {
      queuedComplete = true; break;
    }
    assert.ok(!queue.some((item) => ["FAILED", "INTERRUPTED"].includes(item.status)), "Synthetic queued calls must not fail");
    await sleep(200);
  }
  assert.ok(queuedComplete, "Queued requests must finish sequentially without an open stream");
  const queuedHistory = (await depthOwner.request(`/api/agent?conversationId=${depthConversation.id}`)).messages;
  assert.deepEqual(queuedHistory.filter((item) => item.role === "USER" && item.content.startsWith("Queue probe")).map((item) => item.content), ["Queue probe one", "Queue probe two"]);
  await asyncOwner.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-async-${suffix}@ardor.invalid`, password, displayName: "Async Assessments" } });
  await asyncOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE",
    baseUrl: modelBase, model: "async-evaluation-probe", apiKey: "disposable-fake-model-key" } });
  const queuedLesson = await asyncOwner.request("/api/learning-plans/jobs", { method: "POST", expected: 202,
    body: { requestId: randomUUID(), concept: "Redis 持久化", sourceType: "MANUAL" } });
  assert.ok(["QUEUED", "RUNNING"].includes(queuedLesson.status));
  assert.equal((await asyncOwner.request("/api/learning-plans")).length, 0, "Submission must return before generation finishes");
  await stranger.request(`/api/ai-jobs/${queuedLesson.id}`, { expected: 404 });
  await stranger.request(`/api/ai-jobs/${queuedLesson.id}/retry`, { method: "POST", expected: 404 });
  const readyLesson = await waitForAiJob(asyncOwner, queuedLesson.id);
  const asyncPlanId = new URL(readyLesson.resultPath, "https://ardor.invalid").searchParams.get("id");
  const asyncPlan = await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`);
  const asyncInput = { requestId: randomUUID(), expectedAttemptCount: asyncPlan.attemptCount,
    answers: ["记录写命令", "选择可接受的数据丢失窗口并衡量恢复速度"] };
  const assessmentPath = `/api/learning-plans/${asyncPlanId}/evaluation-jobs`;
  const queuedAssessment = await asyncOwner.request(assessmentPath, { method: "POST", expected: 202, body: asyncInput });
  assert.ok(["QUEUED", "RUNNING"].includes(queuedAssessment.status));
  assert.equal((await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`)).attemptCount, 0);
  await asyncOwner.request(assessmentPath, { method: "POST", expected: 409, body: { ...asyncInput, requestId: randomUUID() } });
  await stranger.request(assessmentPath, { method: "POST", expected: 404, body: { ...asyncInput, requestId: randomUUID() } });
  assert.equal((await stranger.request(`/api/ai-jobs?resourceId=${asyncPlanId}`)).length, 0);
  const failedAssessment = await waitForAiJob(asyncOwner, queuedAssessment.id, "FAILED");
  const failedTaskPath = `/api/background-tasks/LEARNING_EVALUATE/${queuedAssessment.id}`;
  const failedTask = await asyncOwner.request(failedTaskPath);
  assert.equal(failedTask.unread,true);
  await stranger.request(failedTaskPath,{ expected:404 });
  await stranger.request(`${failedTaskPath}/read`,{ method:"POST",expected:404,body:{completionToken:failedTask.completionToken} });
  await stranger.request(`${failedTaskPath}/retry`,{ method:"POST",expected:404,body:{completionToken:failedTask.completionToken} });
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",body:{completionToken:failedTask.completionToken} });
  assert.equal((await asyncOwner.request(failedTaskPath)).unread,false);
  assert.equal(failedAssessment.attempts, 1);
  assert.equal((await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`)).attemptCount, 0);
  await asyncOwner.request(`/api/ai-jobs/${queuedAssessment.id}/retry`, { method: "POST" });
  assert.equal((await waitForAiJob(asyncOwner, queuedAssessment.id)).attempts, 2);
  const graded = await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`);
  const gradedTask = await asyncOwner.request(failedTaskPath);
  assert.equal(gradedTask.unread,true,"A newly completed retry must have its own unread result");
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",expected:409,body:{completionToken:failedTask.completionToken} });
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",body:{completionToken:gradedTask.completionToken} });
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",body:{completionToken:gradedTask.completionToken} });
  await asyncOwner.request("/api/auth/logout",{ method:"POST",expected:204 });
  await asyncOwner.request("/api/auth/login",{ method:"POST",body:{email:`golden-async-${suffix}@ardor.invalid`,password} });
  assert.equal((await asyncOwner.request(failedTaskPath)).unread,false,"Read status must survive a new login session");
  assert.equal(graded.attemptCount, 1);
  assert.equal(graded.lastScore, 65);
  assert.equal(graded.lastEvaluation.attempts[0].items[0].answer, asyncInput.answers[0]);
  assert.equal((await asyncOwner.request(assessmentPath, { method: "POST", expected: 202, body: asyncInput })).status, "COMPLETED");
  await asyncOwner.request(assessmentPath, { method: "POST", expected: 400,
    body: { ...asyncInput, answers: ["changed", "changed"] } });
  assert.equal((await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`)).attemptCount, 1);
  const interview = await asyncOwner.request("/api/interviews", { method: "POST", expected: 201,
    body: { targetRole: "Java 后端工程师", questionCount: 3, modality: "TEXT" } });
  for (let index = 0; index < 3; index++) {
    const progress = await asyncOwner.request(`/api/interviews/${interview.id}/next-question`);
    await asyncOwner.request(`/api/interviews/${interview.id}/answers`, { method: "POST",
      body: { questionId: progress.nextQuestion.id, answerText: "说明技术取舍与实践结果", durationSeconds: 20 } });
  }
  const queuedInterview = await asyncOwner.request(`/api/interviews/${interview.id}/evaluation-jobs`, {
    method: "POST", expected: 202, body: { requestId: randomUUID() } });
  await stranger.request(`/api/interviews/${interview.id}/evaluation-jobs`, {
    method: "POST", expected: 404, body: { requestId: randomUUID() } });
  await waitForAiJob(asyncOwner, queuedInterview.id);
  assert.equal((await asyncOwner.request(`/api/interviews/${interview.id}/evaluation`)).overallScore, 86);
  const canceledInterview = await asyncOwner.request("/api/interviews", { method: "POST", expected: 201,
    body: { targetRole: "Java 后端工程师", questionCount: 3, modality: "TEXT" } });
  for (let index = 0; index < 3; index++) {
    const progress = await asyncOwner.request(`/api/interviews/${canceledInterview.id}/next-question`);
    await asyncOwner.request(`/api/interviews/${canceledInterview.id}/answers`, { method: "POST",
      body: { questionId: progress.nextQuestion.id, answerText: "本题答案", durationSeconds: 20 } });
  }
  const interviewProbe = await (await fetch(new URL("/__probe/interview-started", modelBase))).json();
  const canceledJob = await asyncOwner.request(`/api/interviews/${canceledInterview.id}/evaluation-jobs`, {
    method: "POST", expected: 202, body: { requestId: randomUUID() } });
  let evaluationStarted = false;
  for (let attempt = 0; attempt < 80; attempt++) {
    const now = await (await fetch(new URL("/__probe/interview-started", modelBase))).json();
    if (now.count > interviewProbe.count) { evaluationStarted = true; break; }
    await sleep(100);
  }
  assert.ok(evaluationStarted, "Cancellation test must reach the delayed model call");
  await asyncOwner.request(`/api/interviews/${canceledInterview.id}/cancel`, { method: "POST" });
  await waitForAiJob(asyncOwner, canceledJob.id, "FAILED");
  assert.equal((await asyncOwner.request(`/api/interviews/${canceledInterview.id}`)).status, "CANCELLED");
  await asyncOwner.request(`/api/interviews/${canceledInterview.id}/evaluation`, { expected: 404 });
  const libraryUpload = new FormData();
  libraryUpload.set("file",new Blob(["Redis RDB 快照与 AOF 日志，恢复窗口和性能取舍。"],{type:"text/plain"}),"redis-learning.txt");
  const knowledgeDocument = await asyncOwner.request("/api/knowledge/documents",{ method:"POST",expected:201,body:libraryUpload });
  let vectorTask;
  for (let attempt=0;attempt<100;attempt++) {
    vectorTask = (await asyncOwner.request("/api/background-tasks?limit=100")).find(task => task.kind==="KNOWLEDGE_INDEX" && task.resourceId===knowledgeDocument.id);
    if (vectorTask?.status==="FAILED") break;
    await sleep(100);
  }
  assert.equal(vectorTask?.errorCode,"EMBEDDING_NOT_CONFIGURED");
  await asyncOwner.request("/api/settings/integrations/EMBEDDING",{method:"PUT",body:{provider:"OPENAI_COMPATIBLE",baseUrl:`${modelBase}/v1`,model:"embedding-success-probe",apiKey:"disposable-fake-key"}});
  const vectorTaskPath = `/api/background-tasks/KNOWLEDGE_INDEX/${vectorTask.id}`;
  await stranger.request(`${vectorTaskPath}/retry`,{method:"POST",expected:404,body:{completionToken:vectorTask.completionToken}});
  await asyncOwner.request(`${vectorTaskPath}/retry`,{method:"POST",expected:202,body:{completionToken:vectorTask.completionToken}});
  for (let attempt=0;attempt<100;attempt++) {
    vectorTask = await asyncOwner.request(vectorTaskPath);
    if (vectorTask.status==="COMPLETED") break;
    await sleep(100);
  }
  assert.equal(vectorTask.status,"COMPLETED");
  assert.equal(vectorTask.unread,true);
  assert.equal((await asyncOwner.request("/api/knowledge/index-status")).pendingChunks,0);
  const concurrentSearches=await Promise.all(Array.from({length:4},()=>asyncOwner.request("/api/knowledge/search?query=Redis")));
  assert.ok(concurrentSearches.every(hits=>hits.some(hit=>hit.documentId===knowledgeDocument.id)),"Concurrent retrieval must succeed with the CI's two-connection pool");
  assert.equal((await asyncOwner.request(vectorTaskPath)).completionToken,vectorTask.completionToken,"Searching must not create new completed-index notifications");
  assert.ok((await asyncOwner.request("/api/background-tasks/summary")).unreadByModule.knowledge>0);
  const resumeUpload = new FormData();
  resumeUpload.set("file",new Blob([syntheticResumePdf()],{type:"application/pdf"}),"golden-task-resume.pdf");
  const resume = await asyncOwner.request("/api/resumes",{method:"POST",expected:201,body:resumeUpload});
  assert.equal(resume.parseStatus,"PARSED");
  const resumeJob = await asyncOwner.request(`/api/resumes/${resume.id}/analysis`,{method:"POST",expected:202});
  let resumeTask;
  for (let attempt=0;attempt<100;attempt++) {
    resumeTask = await asyncOwner.request(`/api/background-tasks/RESUME_ANALYSIS/${resumeJob.jobId}`);
    if (resumeTask.status==="COMPLETED") break;
    await sleep(100);
  }
  assert.equal(resumeTask.status,"COMPLETED");
  assert.equal(resumeTask.unread,true);
  assert.equal(resumeTask.resultPath,`/app/resumes?selected=${resume.id}`);
  await stranger.request(`/api/background-tasks/RESUME_ANALYSIS/${resumeJob.jobId}`,{expected:404});
  const taskRecap = await asyncOwner.request("/api/interview-recaps",{method:"POST",expected:202,body:{content:"后台任务验收：技术面试中被问到 Redis 的 RDB 和 AOF 区别，我只说了快照，没能解释日志、重写及故障恢复的数据丢失窗口。"}});
  const taskRecapId=taskRecap.recapId ?? await waitForRecap(asyncOwner,taskRecap.jobId);
  const taskRecapDetail=await asyncOwner.request(`/api/interview-recaps/${taskRecapId}`);
  const taskWeak=taskRecapDetail.questions.find(question=>question.performance==="WEAK");
  const queuedReplay=await asyncOwner.request(`/api/interview-replays/questions/${taskWeak.id}/jobs`,{method:"POST",expected:202,body:{requestId:randomUUID(),answer:"我会比较快照和写命令日志，说明故障恢复的丢失窗口与性能取舍。"}});
  await waitForAiJob(asyncOwner,queuedReplay.id);
  const catalog = await asyncOwner.request("/api/background-tasks?limit=100");
  const taskKinds=["RESUME_ANALYSIS","RECAP_ORGANIZE","KNOWLEDGE_INDEX","LEARNING_CREATE","LEARNING_EVALUATE","REPLAY_EVALUATE","INTERVIEW_EVALUATE"];
  const beforeOwnershipChecks=catalog.map(task=>({id:task.id,status:task.status,attempts:task.attempts,unread:task.unread}));
  for(const kind of taskKinds) {
    const task=catalog.find(item=>item.kind===kind);
    assert.ok(task,`Catalog must include ${kind}`);
    const path=`/api/background-tasks/${kind}/${task.id}`;
    await stranger.request(path,{expected:404});
    await stranger.request(`${path}/read`,{method:"POST",expected:404,body:{completionToken:task.completionToken}});
    await stranger.request(`${path}/retry`,{method:"POST",expected:404,body:{completionToken:task.completionToken}});
  }
  assert.deepEqual((await asyncOwner.request("/api/background-tasks?limit=100")).map(task=>({id:task.id,status:task.status,attempts:task.attempts,unread:task.unread})),beforeOwnershipChecks,"Rejected foreign operations must not change owner tasks or notifications");
  assert.ok((await stranger.request("/api/background-tasks?limit=100")).every(task=>!catalog.some(own=>own.id===task.id)));
  assert.equal((await asyncOwner.request("/api/background-tasks?offset=1&limit=1"))[0].id,catalog[1].id);
  await asyncOwner.request("/api/background-tasks?limit=101",{expected:400});
  await asyncOwner.request("/api/settings/llm",{method:"PUT",body:{provider:"OPENAI_COMPATIBLE",baseUrl:modelBase,model:"restart-ai-probe",apiKey:"disposable-fake-model-key"}});
  const restartCreation = await asyncOwner.request("/api/learning-plans/jobs",{method:"POST",expected:202,body:{requestId:randomUUID(),concept:"Restart persistence fixture",sourceType:"MANUAL"}});
  const restartResult = await waitForAiJob(asyncOwner,restartCreation.id);
  const restartPlanId = new URL(restartResult.resultPath,"https://ardor.invalid").searchParams.get("id");
  await asyncOwner.request(`/api/learning-plans/${restartPlanId}/evaluation-jobs`,{method:"POST",expected:202,body:{requestId:randomUUID(),expectedAttemptCount:0,answers:["记录写命令","restart saved answer"]}});
  let restartReachedProvider=false;
  for(let attempt=0;attempt<100;attempt++) {
    const probe = await (await fetch(new URL("/__probe/restart-started",modelBase))).json();
    if(probe.count>0) { restartReachedProvider=true; break; }
    await sleep(100);
  }
  assert.ok(restartReachedProvider,"Restart acceptance must interrupt an actual pending model call");
  console.log("Golden Path passed: persistent async generation/evaluation/retry/ownership; recap → replay → learning/retest → calendar; Agent confirmation; recovery; concurrent isolation.");
}

await main();

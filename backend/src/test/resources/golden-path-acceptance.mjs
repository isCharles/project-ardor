import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

// This creates real users and records. Run only against an isolated, disposable stack.
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") {
  throw new Error("Golden Path acceptance requires ARDOR_ACCEPTANCE_DISPOSABLE_DB=true");
}

const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const modelBase = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

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
      if (body !== undefined) headers["Content-Type"] = "application/json";
      const csrf = await this.request("/api/auth/csrf");
      headers[csrf.headerName] = csrf.token;
      headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    }
    const response = await fetch(new URL(path, base), {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
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

async function main() {
  const owner = new Client();
  const stranger = new Client();
  const suffix = randomUUID();
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
  assert.equal(calendarTask(tasks, `/app/learning?id=${plan.id}`).status, "TODO");
  assert.equal(calendarTask(tasks, `/app/replay?question=${weak.id}&retest=${retest.id}`).status, "TODO");

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
  console.log("Golden Path passed: recap → replay → learning/retest → calendar; Agent confirmation; failed recap recovery; canceled in-flight retest rollback; concurrent learning isolation.");
}

await main();

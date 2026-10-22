import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { syntheticResumePdf } from "./acceptance-fixtures.mjs";

// This creates real users and records. Run only against an isolated, disposable stack.
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") {
  throw new Error("Golden Path acceptance requires ARDOR_ACCEPTANCE_DISPOSABLE_DB=true");
}

const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const modelBase = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));


class Client {
  cookies = new Map();
  fixtureOpportunityId = null;

  async fixtureOpportunity() {
    if (!this.fixtureOpportunityId) {
      const item = await this.request("/api/opportunities", { method: "POST", expected: 201,
        body: { company: "Ardor Labs", targetRole: "Java 后端工程师" } });
      this.fixtureOpportunityId = item.id;
    }
    return this.fixtureOpportunityId;
  }

  captureCookies(response) {
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }

  async request(path, { method = "GET", body, expected = 200, retryRateLimit = false } = {}) {
    // Valid legacy fixtures now explicitly belong to an owned real interview.
    // Invalid-request tests (expected400) do not use this fixture convenience.
    if (path === "/api/interview-recaps" && method === "POST" && expected === 202 && !body?.jobOpportunityId) {
      body = { ...body, jobOpportunityId: await this.fixtureOpportunity() };
    }
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
    if (response.status === 429 && expected !== 429 && retryRateLimit) {
      const failure = JSON.parse(text);
      const reset = Date.parse(failure.fields?.resetAt ?? "");
      const delay = reset - Date.now();
      if (failure.fields?.dimension === "USER_MINUTE_LIMIT" && Number.isFinite(delay) && delay >= -1000 && delay <= 60000) {
        await sleep(Math.max(0, delay) + 100);
        return this.request(path, { method, body, expected });
      }
    }
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
    if (job?.status === "COMPLETED") {
      if (job.recapId) return job.recapId;
      const draft = await client.request(`/api/interview-recaps/drafts/${jobId}`);
      assert.ok(draft.minutes, "Initial job must produce editable minutes, not a report");
      assert.equal(draft.reviews.length,0,"Review must not start without UI confirmation");
      await client.request(`/api/interview-recaps/drafts/${jobId}/confirm`,{method:"POST",retryRateLimit:true,body:{revision:draft.revision,requestId:randomUUID()}});
      for(let poll=0;poll<100;poll++) {
        const current=await client.request(`/api/interview-recaps/drafts/${jobId}`);
        const review=current.reviews[0];
        if(review?.status === "COMPLETED") return review.recapId;
        if(review?.status === "FAILED") throw new Error("Confirmed review failed");
        await sleep(200);
      }
      throw new Error("Confirmed review timed out");
    }
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
  const sidebarOwner = new Client();
  const sidebarUser = await sidebarOwner.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-sidebar-${suffix}@ardor.invalid`, password, displayName: "Sidebar sync" } });
  const initialSidebar = await sidebarOwner.request("/api/profile/sidebar");
  assert.equal(initialSidebar.configured, false);
  assert.equal(initialSidebar.revision, 0);
  assert.deepEqual(initialSidebar.destinationIds, ["chat", "job-search", "learning", "calendar"]);
  const otherDevice = new Client();
  await otherDevice.request("/api/auth/login", { method: "POST", body: { email: `golden-sidebar-${suffix}@ardor.invalid`, password } });
  const sharedSidebar = await sidebarOwner.request("/api/profile/sidebar", { method: "PUT",
    body: { hrefs: ["/app", "/app/learning"], expectedRevision: 0, expectedUserId: sidebarUser.id } });
  assert.deepEqual(await otherDevice.request("/api/profile/sidebar"), sharedSidebar, "Independent login sessions share account sidebar preferences");
  assert.equal((await stranger.request("/api/profile/sidebar")).configured, false);
  await stranger.request("/api/profile/sidebar", { method: "PUT", expected: 400,
    body: { hrefs: ["/app"], expectedRevision: 0, expectedUserId: sidebarUser.id } });
  await otherDevice.request("/api/profile/sidebar", { method: "PUT", expected: 409,
    body: { hrefs: ["/app/cards"], expectedRevision: 0, expectedUserId: sidebarUser.id } });
  await sidebarOwner.request("/api/profile/sidebar", { method: "PUT", expected: 400,
    body: { hrefs: ["/app/admin"], expectedRevision: 1, expectedUserId: sidebarUser.id } });
  const writes = await Promise.allSettled([sidebarOwner, otherDevice].map((client, index) => client.request("/api/profile/sidebar", {
    method: "PUT", body: { hrefs: [index ? "/app/cards" : "/app/calendar"], expectedRevision: 1, expectedUserId: sidebarUser.id } })));
  assert.equal(writes.filter(result => result.status === "fulfilled").length, 1, "Concurrent writes cannot silently overwrite each other");
  assert.match(String(writes.find(result => result.status === "rejected").reason), /got 409/);
  const savedSidebar = await sidebarOwner.request("/api/profile/sidebar");
  assert.equal(savedSidebar.revision, 2);
  await sidebarOwner.request("/api/profile", { method: "PUT", body: { displayName: "Sidebar sync", headline: "Updated profile", targetRoles: [], timezone: "Asia/Shanghai" } });
  assert.deepEqual(await otherDevice.request("/api/profile/sidebar"), savedSidebar, "Profile edits preserve sidebar preferences");
  const domainSidebar = await sidebarOwner.request("/api/profile/sidebar", { method: "PUT",
    body: { destinationIds: ["job-search", "learning.mock-interviews"], expectedRevision: 2, expectedUserId: sidebarUser.id } });
  assert.deepEqual(domainSidebar.destinationIds, ["chat", "job-search", "learning.mock-interviews"]);
  assert.deepEqual(domainSidebar.hrefs, ["/app", "/app/opportunities", "/app/interviews"]);
  assert.deepEqual(await otherDevice.request("/api/profile/sidebar"), domainSidebar, "Stable IDs must persist in the real database across login sessions");
  const legacyRoundTrip = await otherDevice.request("/api/profile/sidebar", { method: "PUT",
    body: { hrefs: domainSidebar.hrefs, expectedRevision: 3, expectedUserId: sidebarUser.id } });
  assert.deepEqual(legacyRoundTrip.destinationIds, domainSidebar.destinationIds, "Legacy client save must not flatten domain roots sharing a compatibility URL");
  await sidebarOwner.request("/api/profile/sidebar", { method: "PUT", expected: 400,
    body: { destinationIds: ["admin"], expectedRevision: 4, expectedUserId: sidebarUser.id } });
  await stranger.request("/api/profile/sidebar", { method: "PUT", expected: 400,
    body: { destinationIds: ["learning.removed"], expectedRevision: 4, expectedUserId: sidebarUser.id } });
  assert.deepEqual(await sidebarOwner.request("/api/profile/sidebar"), legacyRoundTrip, "Invalid IDs and stale-account requests must not mutate saved preferences");
  await owner.request("/api/settings/llm", {
    method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
      model: "golden-path-model", apiKey: "disposable-fake-model-key" },
  });

  const seriesOwner = new Client();
  await seriesOwner.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-series-edit-${suffix}@ardor.invalid`, password, displayName: "Recurring update" } });
  const recurring = await seriesOwner.request("/api/calendar/series", { method: "POST", expected: 201,
    body: { title: "Original recurring meeting", frequency: "DAILY", timeOfDay: "07:00", occurrenceLimit: 3,
      startDate: new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10) } });
  const originalOccurrences = (await seriesOwner.request("/api/calendar/tasks")).filter(task => task.seriesId === recurring.id);
  assert.equal(originalOccurrences.length, 3);
  const exception = originalOccurrences[0];
  await seriesOwner.request(`/api/calendar/tasks/${exception.id}`, { method: "PUT",
    body: { title: "Personal exception", description: "Keep this one", dueAt: exception.dueAt, priority: exception.priority, status: "TODO" } });
  const revisedRule = await seriesOwner.request(`/api/calendar/series/${recurring.id}`, { method: "PATCH", body: { timeOfDay: "09:00" } });
  assert.equal(revisedRule.series.id, recurring.id);
  assert.equal(revisedRule.updatedOccurrences, 2);
  assert.equal(revisedRule.preservedOccurrences, 1);
  const revisedOccurrences = (await seriesOwner.request("/api/calendar/tasks")).filter(task => task.seriesId === recurring.id);
  assert.deepEqual(revisedOccurrences.map(task => task.id).sort(), originalOccurrences.map(task => task.id).sort());
  for (const task of revisedOccurrences) {
    const original = originalOccurrences.find(item => item.id === task.id);
    assert.equal(new Date(task.dueAt).getTime(), new Date(original.dueAt).getTime() + (task.id === exception.id ? 0 : 7200000));
  }
  await stranger.request(`/api/calendar/series/${recurring.id}`, { method: "PATCH", expected: 404, body: { timeOfDay: "10:00" } });
  await seriesOwner.request(`/api/calendar/series/${recurring.id}`, { method: "DELETE" });
  await seriesOwner.request(`/api/calendar/series/${recurring.id}`, { method: "PATCH", expected: 400, body: { timeOfDay: "10:00" } });

  const submitted = await owner.request("/api/interview-recaps", {
    method: "POST", expected: 202,
    body: { content: "我在 Ardor Labs 的 Java 后端工程师面试里被问到 Redis 的 RDB 和 AOF 有什么区别。我只说 RDB 是快照，没能解释 AOF 的写命令日志、重写和恢复取舍。" },
  });
  const recapId = submitted.recapId ?? await waitForRecap(owner, submitted.jobId);
  const recap = await owner.request(`/api/interview-recaps/${recapId}`);
  assert.equal(recap.jobOpportunityId, owner.fixtureOpportunityId);
  assert.equal("company" in recap, false, "Metadata belongs only to the interview process");
  const recapProcess = await owner.request(`/api/opportunities/${recap.jobOpportunityId}`);
  assert.equal(recapProcess.company, "Ardor Labs");
  await stranger.request(`/api/opportunities/${recap.jobOpportunityId}`, { expected: 404 });
  await stranger.request("/api/interview-recaps", { method: "POST", expected: 404,
    body: { content: "Synthetic interview content long enough to pass input validation.", jobOpportunityId: recap.jobOpportunityId } });
  await owner.request("/api/interview-recaps", { method: "POST", expected: 400,
    body: { content: "Synthetic interview content without a selected interview process." } });
  for (const inaccessible of [recap.jobOpportunityId, randomUUID()]) {
    await stranger.request("/api/interviews", { method: "POST", expected: 404,
      body: { targetRole: "Engineer", questionCount: 3, jobOpportunityId: inaccessible } });
  }
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
  const rearrangedAt = new Date(new Date(dueAt).getTime() + 3600000).toISOString();
  const changedPlan = await owner.request(`/api/learning-plans/${plan.id}/arrangement`, {
    method: "PATCH", body: { reason: "Synthetic updated reason", scheduledAt: rearrangedAt },
  });
  assert.equal(new Date(changedPlan.scheduledAt).getTime(), new Date(rearrangedAt).getTime());
  assert.deepEqual(changedPlan.lesson, plan.lesson);
  assert.deepEqual(changedPlan.exercises, plan.exercises);
  const changedCalendar = calendarTask(await owner.request("/api/calendar/tasks"), `/app/learning?id=${plan.id}`);
  assert.equal(new Date(changedCalendar.dueAt).getTime(), new Date(rearrangedAt).getTime());
  await stranger.request(`/api/learning-plans/${plan.id}/arrangement`, { method: "PATCH", expected: 404,
    body: { reason: "Unauthorized overwrite" } });
  await owner.request(`/api/learning-plans/${plan.id}/arrangement`, {
    method: "PATCH", body: { reason: planInput.reason, scheduledAt: dueAt },
  });
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
  const proposalMessageId = restored.messages.at(-1).id;
  const resolutionPath = `/api/agent/messages/${proposalMessageId}/proposal-resolution`;
  const decision = { key: proposal.learningPlan.requestId, resolution: "DISMISSED" };
  await stranger.request(resolutionPath, { method: "PUT", expected: 404, body: decision });
  await owner.request(resolutionPath, { method: "PUT", body: decision });
  await owner.request(resolutionPath, { method: "PUT", body: { ...decision, resolution: "DONE" } });
  const resolvedHistory = await owner.request(`/api/agent?conversationId=${conversation.id}`);
  assert.equal(resolvedHistory.messages.at(-1).proposalResolutions[decision.key], "DISMISSED",
    "A final presentation decision is durable and cannot be revived or overwritten by stale requests");
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
  recovered.recapId = await waitForRecap(recoveryOwner, failedJob.jobId);
  assert.ok(recovered.recapId);
  const duplicateSubmission = await recoveryOwner.request("/api/interview-recaps", {
    method: "POST", expected: 202, body: { content: recoveryInput }, retryRateLimit: true,
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
  assert.equal(incompleteRecap.jobOpportunityId, metadataOwner.fixtureOpportunityId);
  assert.equal("company" in incompleteRecap, false);
  assert.equal("targetRole" in incompleteRecap, false);
  assert.equal("occurredAt" in incompleteRecap, false);

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
  await depthOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
    model: "queue-failure-probe", apiKey: "disposable-fake-model-key" } });
  const failureQueued = { ...queuedFirst, requestId: randomUUID(), message: "Queue failure marker" };
  const pausedQueued = { ...queuedSecond, requestId: randomUUID(), message: "Queue continuation after review" };
  await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: failureQueued });
  await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: pausedQueued });
  let pausedAfterFailure = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queued = await depthOwner.request(`/api/agent/queue?conversationId=${depthConversation.id}`);
    if (queued.find((item) => item.id === failureQueued.requestId)?.status === "FAILED") {
      assert.equal(queued.find((item) => item.id === pausedQueued.requestId)?.status, "QUEUED");
      pausedAfterFailure = true; break;
    }
    await sleep(200);
  }
  assert.ok(pausedAfterFailure, "Failure must pause its queued successor");
  await sleep(1500);
  assert.equal((await depthOwner.request(`/api/agent/queue?conversationId=${depthConversation.id}`)).find((item) => item.id === pausedQueued.requestId)?.status, "QUEUED");
  const urgent = { ...queuedSecond, requestId: randomUUID(), message: "Handle this correction first" };
  await depthOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: urgent });
  await owner.request(`/api/agent/queue/${urgent.requestId}/steer`, { method: "POST", expected: 404, body: { interruptRunId: null } });
  await depthOwner.request(`/api/agent/queue/${urgent.requestId}/steer`, { method: "POST", expected: 204, body: { interruptRunId: null } });
  let urgentFinished = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queued = await depthOwner.request(`/api/agent/queue?conversationId=${depthConversation.id}`);
    assert.equal(queued.find(item => item.id === pausedQueued.requestId)?.status, "QUEUED", "Prioritizing a correction must not release older paused work");
    if (queued.find(item => item.id === urgent.requestId)?.status === "COMPLETED") { urgentFinished = true; break; }
    await sleep(100);
  }
  assert.ok(urgentFinished);
  await owner.request(`/api/agent/queue/${pausedQueued.requestId}/resume`, { method: "POST", expected: 404 });
  await depthOwner.request(`/api/agent/queue/${pausedQueued.requestId}/resume`, { method: "POST", expected: 204 });
  let resumed = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queued = await depthOwner.request(`/api/agent/queue?conversationId=${depthConversation.id}`);
    if (queued.find((item) => item.id === pausedQueued.requestId)?.status === "COMPLETED") { resumed = true; break; }
    await sleep(200);
  }
  assert.ok(resumed, "Explicit acknowledgement must resume only the queued successor");
  // Snapshot deletion cannot expand to later data, replay, or another user's records.
  const snapshotOwner = new Client();
  await snapshotOwner.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-snapshot-${suffix}@ardor.invalid`, password, displayName: "Snapshot probe" } });
  await snapshotOwner.request("/api/agent/memory", { method: "PUT", body: { content: "Old snapshot item" } });
  const [snapshotOld] = await snapshotOwner.request("/api/agent/memory/items");
  await snapshotOwner.request("/api/agent/memory", { method: "PUT", body: { content: "Old snapshot item\nLater item" } });
  const snapshotNew = (await snapshotOwner.request("/api/agent/memory/items")).find(item => item.id !== snapshotOld.id);
  await stranger.request("/api/agent/memory/snapshot", { method: "DELETE", expected: 404, body: { ids: [snapshotOld.id] } });
  await snapshotOwner.request("/api/agent/memory/snapshot", { method: "DELETE", expected: 404, body: { ids: [snapshotOld.id, randomUUID()] } });
  assert.equal((await snapshotOwner.request("/api/agent/memory/items")).length, 2);
  await snapshotOwner.request("/api/agent/memory/snapshot", { method: "DELETE", expected: 204, body: { ids: [snapshotOld.id] } });
  await snapshotOwner.request("/api/agent/memory/snapshot", { method: "DELETE", expected: 404, body: { ids: [snapshotOld.id] } });
  assert.deepEqual((await snapshotOwner.request("/api/agent/memory/items")).map(item => item.id), [snapshotNew.id]);
  // Reads are immediate; writes wait for an exact owner confirmation.
  for (const allow of [false, true]) {
    const permissionOwner = new Client();
    await permissionOwner.request("/api/auth/register", { method: "POST", expected: 201,
      body: { email: `golden-permission-${allow}-${suffix}@ardor.invalid`, password, displayName: "Permission probe" } });
    await permissionOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE",
      baseUrl: modelBase, model: "approval-integration-probe", apiKey: "disposable-fake-model-key" } });
    const permissionConversation = await permissionOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
    const permissionRunId = randomUUID();
    await permissionOwner.request("/api/agent/queue", { method: "POST", expected: 202,
      body: { requestId: permissionRunId, conversationId: permissionConversation.id, message: "Remember Synthetic approved preference" } });
    const approvalsPath = `/api/agent/runs/${permissionRunId}/approvals`;
    let decisionSent = false;
    let permissionFinished = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      try {
        const proposals = await permissionOwner.request(approvalsPath);
        const pending = proposals.find(item => item.status === "PENDING");
        if (pending && !decisionSent) {
          assert.equal((await permissionOwner.request("/api/agent/memory/items")).length,0,"No write before confirmation");
          await stranger.request(`${approvalsPath}/${pending.id}`,{method:"POST",expected:404,body:{allow:true}});
          await permissionOwner.request(`${approvalsPath}/${pending.id}`,{method:"POST",expected:204,body:{allow}});
          decisionSent = true;
        }
        const run = await permissionOwner.request(`/api/agent/runs/${permissionRunId}`);
        if (run.status === "COMPLETED") { permissionFinished = true; break; }
        assert.notEqual(run.status, "FAILED");
      }
      catch (error) { if (!error.message.includes("got 404")) throw error; }
      await sleep(100);
    }
    assert.ok(permissionFinished);
    assert.ok(decisionSent);
    await stranger.request(approvalsPath, { expected: 404 });
    const savedMemories = await permissionOwner.request("/api/agent/memory/items");
    assert.equal(savedMemories.length, allow ? 1 : 0);
    if (!allow) continue;
    assert.equal(savedMemories[0].content, "Synthetic approved preference");
    await permissionOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE",
      baseUrl: modelBase, model: "deletion-proposal-integration-probe", apiKey: "disposable-fake-model-key" } });
    const deletionConversation = await permissionOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
    const deletionRunId = randomUUID();
    await permissionOwner.request("/api/agent/queue", { method: "POST", expected: 202,
      body: { requestId: deletionRunId, conversationId: deletionConversation.id, message: `Delete fixture memory ${savedMemories[0].id}` } });
    let deletionFinished = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      const queued = await permissionOwner.request(`/api/agent/queue?conversationId=${deletionConversation.id}`);
      if (queued.find(item => item.id === deletionRunId)?.status === "COMPLETED") { deletionFinished = true; break; }
      await sleep(100);
    }
    assert.ok(deletionFinished);
    const deletionHistory = await permissionOwner.request(`/api/agent?conversationId=${deletionConversation.id}`);
    const deletionReply = deletionHistory.messages.filter(item => item.role === "ASSISTANT").at(-1);
    const proposal = JSON.parse(deletionReply.runTrace).proposals.find(item => item.kind === "memory_item");
    assert.equal(proposal.targetId, savedMemories[0].id);
    assert.equal((await permissionOwner.request("/api/agent/memory/items")).length, 1, "A deletion proposal cannot delete by itself");
    await stranger.request(proposal.endpoint, { method: "DELETE", expected: 404 });
    if (allow) await permissionOwner.request(proposal.endpoint, { method: "DELETE", expected: 204 });
    assert.equal((await permissionOwner.request("/api/agent/memory/items")).length, allow ? 0 : 1, "Only the explicit final delete request may remove data");
  }

  const silentOwner = new Client();
  const memoryEditor = new Client();
  await memoryEditor.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-memory-edit-${suffix}@ardor.invalid`, password, displayName: "Concurrent memory edit" } });
  await memoryEditor.request("/api/agent/memory", { method: "PUT", body: { content: "First memory\nSecond memory" } });
  const memoryItems = await memoryEditor.request("/api/agent/memory/items");
  const memoryEdits = await Promise.allSettled(memoryItems.map(item => memoryEditor.request(`/api/agent/memory/items/${item.id}`,
    { method: "PATCH", body: { content: "Same revised memory" } })));
  assert.equal(memoryEdits.filter(result => result.status === "fulfilled").length, 1);
  assert.match(memoryEdits.find(result => result.status === "rejected").reason.message, /got 400/);
  assert.equal((await memoryEditor.request("/api/agent/memory/items")).filter(item => item.content === "Same revised memory").length, 1);
  await owner.request(`/api/agent/memory/items/${memoryItems[0].id}`, { method: "PATCH", expected: 404, body: { content: "Foreign edit" } });
  await silentOwner.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `golden-silence-${suffix}@ardor.invalid`, password, displayName: "Silent stream probe" } });
  await silentOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
    model: "agent-silence-probe", apiKey: "disposable-fake-model-key" } });
  const silentConversation = await silentOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const silentRequest = { requestId: randomUUID(), conversationId: silentConversation.id, message: "Silent stream timeout probe", reasoningDepth: "DEEP" };
  await silentOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: silentRequest });
  let silenceFailed = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const pending = await silentOwner.request(`/api/agent/queue?conversationId=${silentConversation.id}`);
    if (pending.find((item) => item.id === silentRequest.requestId)?.status === "FAILED") { silenceFailed = true; break; }
    await sleep(200);
  }
  assert.ok(silenceFailed, "An open HTTP connection without model output must not run indefinitely");
  const silentRun = await silentOwner.request(`/api/agent/runs/${silentRequest.requestId}`);
  assert.equal(silentRun.errorCode, "AGENT_UPSTREAM_SILENT");
  assert.equal(silentRun.retryable, false);
  const savedSilence = await silentOwner.request(`/api/agent/runs/${silentRequest.requestId}/progress`);
  assert.equal(savedSilence.phase, "FAILED");
  assert.equal(savedSilence.thinkingOutput, "");
  await owner.request(`/api/agent/runs/${silentRequest.requestId}/progress`, { expected: 404 });
  await silentOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
    model: "agent-cancel-probe", apiKey: "disposable-fake-model-key" } });
  const steeringResumeUpload = new FormData();
  steeringResumeUpload.set("file", new Blob([syntheticResumePdf()], { type: "application/pdf" }), "steering-context.pdf");
  const steeringResume = await silentOwner.request("/api/resumes", { method: "POST", expected: 201, body: steeringResumeUpload });
  const cancelRequest = { ...silentRequest, requestId: randomUUID(), message: "Explicit user cancellation probe: 删除1、2、3，然后重新生成",
    contextReferences: [{ type: "RESUME", id: steeringResume.id }] };
  await silentOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: cancelRequest });
  await silentOwner.request(`/api/agent/queue/${cancelRequest.requestId}/resume`, { method: "POST", expected: 204 });
  let cancelStarted = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const pending = await silentOwner.request(`/api/agent/queue?conversationId=${silentConversation.id}`);
    if (pending.find((item) => item.id === cancelRequest.requestId)?.status === "RUNNING") { cancelStarted = true; break; }
    await sleep(100);
  }
  assert.ok(cancelStarted);
  await owner.request(`/api/agent/runs/${cancelRequest.requestId}/cancel`, { method: "POST", expected: 404 });
  await silentOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase,
    model: "steering-context-probe", apiKey: "disposable-fake-model-key" } });
  const steerRequest = { ...cancelRequest, contextReferences: [], requestId: randomUUID(), message: "等等，2不需要重新生成", interruptedRunId: cancelRequest.requestId };
  const unrelatedConversation = await silentOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const supersededRequest = { ...steerRequest, requestId: randomUUID(), message: "Synthetic superseded follow-up" };
  await silentOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: supersededRequest });
  const missingResumeUpload = new FormData();
  missingResumeUpload.set("file", new Blob([syntheticResumePdf()], { type: "application/pdf" }), "removed-before-dispatch.pdf");
  const missingResume = await silentOwner.request("/api/resumes", { method: "POST", expected: 201, body: missingResumeUpload });
  const dependentRequest = { ...steerRequest, requestId: randomUUID(), conversationId: unrelatedConversation.id,
    interruptedRunId: null, contextReferences: [{ type: "RESUME", id: missingResume.id }], message: "Synthetic other-conversation dependent" };
  await silentOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: dependentRequest });
  await silentOwner.request(`/api/resumes/${missingResume.id}`, { method: "DELETE", expected: 204 });
  await silentOwner.request("/api/agent/messages/steer", { method: "POST", expected: 400, body: { ...steerRequest, requestId: randomUUID(), conversationId: unrelatedConversation.id } });
  assert.equal((await silentOwner.request(`/api/agent/runs/${cancelRequest.requestId}`)).status, "RUNNING", "Cross-conversation steering must not stop the original");
  await owner.request("/api/agent/messages/steer", { method: "POST", expected: 404, body: steerRequest });
  await silentOwner.request("/api/agent/messages/steer", { method: "POST", expected: 202, body: steerRequest });
  await silentOwner.request("/api/agent/messages/steer", { method: "POST", expected: 202, body: steerRequest });
  assert.equal((await silentOwner.request(`/api/agent/runs/${cancelRequest.requestId}`)).errorCode, "AGENT_RUN_CANCELLED");
  let steerFinished = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queued = await silentOwner.request(`/api/agent/queue?conversationId=${silentConversation.id}`);
    if (queued.find(item => item.id === steerRequest.requestId)?.status === "COMPLETED") { steerFinished = true; break; }
    await sleep(100);
  }
  assert.ok(steerFinished, "Interrupting must dispatch the selected new instruction rather than replaying the old run");
  const correctedHistory = (await silentOwner.request(`/api/agent?conversationId=${silentConversation.id}`)).messages;
  assert.equal(correctedHistory.filter(item => item.role === "USER" && item.content === cancelRequest.message).length, 1, "Interrupted original stays in history exactly once");
  assert.equal(correctedHistory.find(item => item.role === "USER" && item.content === cancelRequest.message).contextReferences[0].id, steeringResume.id,
    "Interrupted attachments survive without a completed message row");
  assert.equal(correctedHistory.filter(item => item.role === "USER" && item.content === supersededRequest.message).length, 1,
    "Superseded pending messages remain in history");
  assert.equal(correctedHistory.filter(item => item.role === "USER" && item.content === steerRequest.message).length, 1);
  assert.ok(correctedHistory.some(item => item.role === "ASSISTANT" && item.content.includes("只重新生成1和3")), "Fake provider requires both actual user turns in its received context");
  let dependentReleased = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const item = (await silentOwner.request(`/api/agent/queue?conversationId=${unrelatedConversation.id}`)).find(item => item.id === dependentRequest.requestId);
    if (item?.status === "FAILED") { dependentReleased = true; break; }
    await sleep(100);
  }
  assert.ok(dependentReleased, "Superseding one conversation must rewire another conversation's dependency");
  await silentOwner.request(`/api/agent/runs/${dependentRequest.requestId}`, { expected: 404 });
  const preRunHistory = (await silentOwner.request(`/api/agent?conversationId=${unrelatedConversation.id}`)).messages;
  assert.equal(preRunHistory.filter(item => item.role === "USER" && item.content === dependentRequest.message).length, 1,
    "Accepted messages that fail before run creation remain visible");
  assert.ok(preRunHistory.some(item => item.role === "ASSISTANT" && item.content.includes("本条未能开始")));
  await sleep(9000); // The fake provider attempts a late tool call after the local run has stopped.
  assert.equal((await silentOwner.request("/api/agent/memory")).content, "", "Late tool chunks must not mutate memory");
  assert.equal((await silentOwner.request(`/api/agent/runs/${silentRequest.requestId}`)).errorCode, "AGENT_UPSTREAM_SILENT");
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
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",expected:204,body:{completionToken:failedTask.completionToken} });
  assert.equal((await asyncOwner.request(failedTaskPath)).unread,false);
  assert.equal(failedAssessment.attempts, 1);
  assert.equal((await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`)).attemptCount, 0);
  await asyncOwner.request(`/api/ai-jobs/${queuedAssessment.id}/retry`, { method: "POST" });
  assert.equal((await waitForAiJob(asyncOwner, queuedAssessment.id)).attempts, 2);
  const graded = await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`);
  const gradedTask = await asyncOwner.request(failedTaskPath);
  assert.equal(gradedTask.unread,true,"A newly completed retry must have its own unread result");
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",expected:409,body:{completionToken:failedTask.completionToken} });
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",expected:204,body:{completionToken:gradedTask.completionToken} });
  await asyncOwner.request(`${failedTaskPath}/read`,{ method:"POST",expected:204,body:{completionToken:gradedTask.completionToken} });
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
  await asyncOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE",
    baseUrl: modelBase, model: "date-correction-probe", apiKey: "disposable-fake-model-key" } });
  const dateConversation = await asyncOwner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const correctedDate = new Date(Date.now() + 3 * 86400000).toISOString();
  const dateRequest = { requestId: randomUUID(), conversationId: dateConversation.id,
    message: `请把这个日期改到${correctedDate}。目标 ${asyncPlanId}`, contextReferences: [{ type: "LEARNING", id: asyncPlanId }] };
  await asyncOwner.request("/api/agent/queue", { method: "POST", expected: 202, body: dateRequest });
  let dateFinished = false;
  let dateConfirmed = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    const queued = await asyncOwner.request(`/api/agent/queue?conversationId=${dateConversation.id}`);
    if (!dateConfirmed) {
      try {
        const proposals=await asyncOwner.request(`/api/agent/runs/${dateRequest.requestId}/approvals`);
        const pending=proposals.find(item=>item.status==="PENDING");
        if(pending) {
          assert.equal((await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`)).scheduledAt,graded.scheduledAt,"Date stays unchanged before confirmation");
          await asyncOwner.request(`/api/agent/runs/${dateRequest.requestId}/approvals/${pending.id}`,{method:"POST",expected:204,body:{allow:true}});
          dateConfirmed=true;
        }
      } catch(error) { if(!error.message.includes("got 404")) throw error; }
    }
    if (queued.find(item => item.id === dateRequest.requestId)?.status === "COMPLETED") { dateFinished = true; break; }
    await sleep(100);
  }
  assert.ok(dateFinished && dateConfirmed, "A date update executes only after explicit confirmation");
  const rescheduledLesson = await asyncOwner.request(`/api/learning-plans/${asyncPlanId}`);
  assert.equal(new Date(rescheduledLesson.scheduledAt).getTime(), new Date(correctedDate).getTime());
  assert.deepEqual(rescheduledLesson.lesson, graded.lesson);
  assert.equal(rescheduledLesson.attemptCount, 1);
  assert.equal(rescheduledLesson.lastScore, 65);
  await asyncOwner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE",
    baseUrl: modelBase, model: "async-evaluation-probe", apiKey: "disposable-fake-model-key" } });
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
  await stranger.request("/api/background-tasks/modules/resumes/read",{method:"POST",expected:204});
  assert.equal((await asyncOwner.request(`/api/background-tasks/RESUME_ANALYSIS/${resumeJob.jobId}`)).unread,true,
    "Another owner's module read must not consume this owner's notification");
  await asyncOwner.request("/api/background-tasks/modules/resumes/read",{method:"POST",expected:204});
  assert.equal((await asyncOwner.request(`/api/background-tasks/RESUME_ANALYSIS/${resumeJob.jobId}`)).unread,false);
  const firstScore = await asyncOwner.request(`/api/resumes/${resume.id}/analysis`);
  assert.equal(firstScore.promptVersion,"resume-analysis-v6");
  assert.equal(firstScore.analysis.rubricVersion,"ardor-resume-rubric-v3");
  assert.ok(firstScore.analysis.dimensionRatings.every(item=>item.level===0));
  assert.equal(firstScore.analysis.rubricDimensions.length,6);
  assert.equal(firstScore.analysis.overallScore,0,"The server must ignore the fixture model's arbitrary overallScore=82");
  assert.equal(firstScore.analysis.dimensionRatings.length,6);
  assert.equal(firstScore.analysis.issues[0].severity,"B");
  const duplicateUpload = new FormData();
  duplicateUpload.set("file",new Blob([syntheticResumePdf()],{type:"application/pdf"}),"different-filename-same-text.pdf");
  const duplicateResume = await asyncOwner.request("/api/resumes",{method:"POST",expected:201,body:duplicateUpload});
  await asyncOwner.request(`/api/resumes/${duplicateResume.id}/analysis`,{method:"POST",expected:202});
  for (let attempt=0;attempt<100;attempt++) {
    const status = await asyncOwner.request(`/api/resumes/${duplicateResume.id}/analysis/status`);
    if(status.status==="COMPLETED") break;
    if(status.status==="FAILED") throw new Error(status.errorMessage);
    await sleep(100);
  }
  const duplicateScore = await asyncOwner.request(`/api/resumes/${duplicateResume.id}/analysis`);
  assert.deepEqual(duplicateScore.analysis,firstScore.analysis,"Identical owned text and conditions reuse the canonical assessment");
  assert.notEqual(duplicateScore.id,firstScore.id,"Each uploaded resume retains its own report association");
  await stranger.request(`/api/resumes/${resume.id}/analysis/history`,{expected:404});
  await stranger.request(`/api/resumes/${resume.id}/analysis?analysisId=${firstScore.id}`,{expected:404});
  const changedConditions={targetRole:"Java backend",jobDescription:"Design services with Redis and PostgreSQL. Explain reliability and performance tradeoffs."};
  const versionJob=await asyncOwner.request(`/api/resumes/${resume.id}/analysis`,{method:"POST",expected:202,body:changedConditions});
  assert.notEqual(versionJob.analysisId,firstScore.id);
  assert.equal((await asyncOwner.request(`/api/resumes/${resume.id}/analysis?analysisId=${firstScore.id}`)).id,firstScore.id,"Old reports stay readable while a new assessment runs");
  for(let attempt=0;attempt<100;attempt++) {
    const status=await asyncOwner.request(`/api/resumes/${resume.id}/analysis/status`);
    if(status.status==="COMPLETED") break;
    if(status.status==="FAILED") throw new Error(status.errorMessage);
    await sleep(100);
  }
  const changedScore=await asyncOwner.request(`/api/resumes/${resume.id}/analysis`);
  assert.notEqual(changedScore.analysis.evaluationKey,firstScore.analysis.evaluationKey);
  assert.equal(changedScore.analysis.scoringContext.mode,"USER_JD");
  assert.equal((await asyncOwner.request(`/api/resumes/${resume.id}/analysis/history`)).length,2);
  const repeatScore=await asyncOwner.request(`/api/resumes/${resume.id}/analysis`,{method:"POST",expected:202,body:changedConditions,retryRateLimit:true});
  assert.equal(repeatScore.status,"COMPLETED");
  assert.equal(repeatScore.analysisId,changedScore.id);
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

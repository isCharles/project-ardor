import assert from "node:assert/strict";
import {createHash, randomUUID} from "node:crypto";
import {readFile, writeFile, mkdir} from "node:fs/promises";
import {execFileSync} from "node:child_process";
import {dirname, join} from "node:path";
import {syntheticResumePdf} from "./acceptance-fixtures.mjs";

// This is a two-phase probe, not a production backup tool. No real credentials or uploads.
assert.equal(process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB, "true", "Disposable stack required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE;
assert.ok(base, "Explicit disposable API required");
const url = new URL(base);
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(url.hostname));
assert.ok(url.protocol === "http:" && !["", "80", "443", "3000", "8080"].includes(url.port), "Use a separate local fixture port");
assert.ok(url.pathname === "/" && !url.search && !url.hash && !url.username && !url.password, "Fixture API must be a plain server root");
const mode = process.argv[2];
assert.ok(["seed", "verify"].includes(mode), "Expected seed or verify");
const manifestPath = process.env.ARDOR_RECOVERY_MANIFEST;
assert.ok(manifestPath, "Explicit synthetic manifest path required");
const cookies = new Map();
const database = process.env.ARDOR_RECOVERY_DB_CONTAINER;
assert.equal(database, mode === "seed" ? "ardor-recovery-db" : "ardor-recovery-restored", "Dedicated fixture database required");
function docker(...args) {
  return execFileSync("docker", args, {encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 15000}).trim();
}
function storageKey(userId, resumeId) {
  for (const id of [userId, resumeId]) assert.match(id, /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i);
  const key = docker("exec", database, "psql", "-U", "fixture", "-d", "recovery", "-At", "-c",
    `SELECT storage_key FROM resumes WHERE id='${resumeId}' AND user_id='${userId}'`);
  assert.match(key, /^[0-9a-f-]{36}\.pdf$/i, "Exactly one synthetic resume storage key required");
  return key;
}
function checkConfig(config) {
  assert.equal(config.configured, true);
  assert.equal(config.configurationSource, "PERSONAL");
  assert.equal(config.personalOverride, true);
  assert.equal(config.provider, "OPENAI_COMPATIBLE");
  assert.equal(config.baseUrl, "http://ardor-recovery-model:8090");
  assert.equal(config.model, "golden-path-model");
  return config;
}

async function request(path, method = "GET", body, expected = 200) {
  const headers = {};
  if (method !== "GET") {const csrf = await request("/api/auth/csrf"); headers[csrf.headerName] = csrf.token;}
  headers.Cookie = [...cookies].map(([k,v]) => `${k}=${v}`).join("; ");
  const form = body instanceof FormData;
  if (body !== undefined && !form) headers["Content-Type"] = "application/json";
  const response = await fetch(base + path, {method, headers, body: body === undefined ? undefined : form ? body : JSON.stringify(body)});
  for (const line of response.headers.getSetCookie()) {
    const pair = line.split(";")[0], index = pair.indexOf("=");
    cookies.set(pair.slice(0,index), pair.slice(index+1));
  }
  assert.equal(response.status, expected, `${method} ${path} status`);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function analyze(resumeId, role) {
  await request(`/api/resumes/${resumeId}/analysis`, "POST", {targetRole: role, jobDescription: "Synthetic Java Spring Boot role; no real employer."}, 202);
  for (let attempt = 0; attempt < 160; attempt++) {
    const task = await request(`/api/resumes/${resumeId}/analysis/status`);
    assert.notEqual(task.status, "FAILED", `Synthetic analysis failed: ${task.errorCode}`);
    if (task.status === "COMPLETED") return task.analysisId;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error("Synthetic analysis did not complete");
}

if (mode === "seed") {
  const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE;
  assert.ok(model, "Explicit fake model endpoint required");
  const modelUrl = new URL(model);
  assert.equal(modelUrl.hostname, "ardor-recovery-model", "Use the dedicated fixture model container, not a real provider");
  assert.equal(modelUrl.port, "8090");
  const suffix = randomUUID(), email = `recovery-${suffix}@ardor.invalid`, password = `Synthetic-recovery-${suffix}!`;
  const user = await request("/api/auth/register", "POST", {email, password, displayName: "Synthetic recovery probe"}, 201);
  await request("/api/settings/llm", "PUT", {provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "golden-path-model", apiKey: "fake-only"});
  const config = checkConfig(await request("/api/settings/llm"));
  assert.equal(docker("exec", "ardor-recovery-model", "printenv", "ARDOR_FAKE_EXPECTED_API_KEY"), "fake-only", "Fake model must validate the restored credential");
  const pdf = syntheticResumePdf();
  const upload = new FormData();
  upload.append("file", new Blob([pdf], {type: "application/pdf"}), "synthetic-recovery.pdf");
  const resume = await request("/api/resumes", "POST", upload, 201);
  assert.equal(resume.parseStatus, "PARSED");
  const checksum = createHash("sha256").update(pdf).digest("hex");
  assert.equal(resume.checksumSha256.toLowerCase(), checksum);
  const analysisId = await analyze(resume.id, "Synthetic baseline before restore");
  const card = await request("/api/memory-cards", "POST", {sourceType: "AGENT", front: "Synthetic recovery question", back: "Restore data, files and the correct encryption configuration."}, 201);
  await request(`/api/memory-cards/${card.id}/reviews`, "POST", {rating: "GOOD"});
  await mkdir(dirname(manifestPath), {recursive: true});
  await writeFile(manifestPath, JSON.stringify({kind: "ardor-synthetic-recovery-v2", suffix, email, password, userId: user.id,
    resumeId: resume.id, storageKey: storageKey(user.id, resume.id), config, checksum, analysisId, cardId: card.id}), {encoding: "utf8", flag: "wx", mode: 0o600});
  console.log("PASS seed: synthetic login, encrypted fake provider config, parsed PDF, analysis and reviewed independent card; manifest created without printing credentials.");
} else {
  const fixture = JSON.parse(await readFile(manifestPath, "utf8"));
  assert.equal(fixture.kind, "ardor-synthetic-recovery-v2");
  assert.match(fixture.suffix, /^[0-9a-f-]{36}$/i);
  assert.equal(fixture.email, `recovery-${fixture.suffix}@ardor.invalid`);
  assert.equal(fixture.password, `Synthetic-recovery-${fixture.suffix}!`);
  const uploadRoot = process.env.ARDOR_RECOVERY_UPLOAD_ROOT;
  assert.ok(uploadRoot, "Explicit restored synthetic upload directory required");
  const key = storageKey(fixture.userId, fixture.resumeId);
  assert.equal(key, fixture.storageKey, "Restored row retains its exact file association");
  assert.equal(createHash("sha256").update(await readFile(join(uploadRoot, key))).digest("hex"), fixture.checksum);
  const configuredRoot = docker("exec", "ardor-recovery-backend", "printenv", "ARDOR_RESUME_STORAGE");
  assert.equal(configuredRoot, "/app/data/resumes", "Use the explicit synthetic application storage root");
  assert.equal(docker("exec", "ardor-recovery-backend", "sha256sum", `${configuredRoot}/${key}`).split(/\s+/)[0], fixture.checksum,
    "The application container must resolve the exact restored storage key to the original bytes");
  const user = await request("/api/auth/login", "POST", {email: fixture.email, password: fixture.password});
  assert.equal(user.id, fixture.userId, "Restored authentication keeps the same user identity");
  const resume = (await request("/api/resumes")).find(row => row.id === fixture.resumeId);
  assert.ok(resume);
  assert.equal(resume.checksumSha256.toLowerCase(), fixture.checksum);
  assert.equal(resume.parseStatus, "PARSED");
  const card = (await request("/api/memory-cards")).find(row => row.id === fixture.cardId);
  assert.ok(card);
  assert.equal(card.repetitions, 1, "Review progress survives restore");
  const history = await request(`/api/resumes/${fixture.resumeId}/analysis/history`);
  assert.ok(history.some(row => row.id === fixture.analysisId), "Historical report identity survives restore");
  const config = await request("/api/settings/llm");
  assert.deepEqual(checkConfig(config), fixture.config, "Original personal provider settings must survive restore, not an inherited fallback");
  assert.equal(docker("exec", "ardor-recovery-model", "printenv", "ARDOR_FAKE_EXPECTED_API_KEY"), "fake-only");
  // No key is supplied after restore. A new context forces an uncached model call using the stored encrypted key.
  const fresh = await analyze(fixture.resumeId, `Synthetic restored call ${randomUUID()}`);
  assert.notEqual(fresh, fixture.analysisId);
  console.log("PASS verify: restored PDF bytes, fresh login with original identity, resume/report/card identities and review progress preserved; new uncached analysis uses restored encrypted fake-provider config.");
}

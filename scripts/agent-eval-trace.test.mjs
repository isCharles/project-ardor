import { test } from "node:test";
import assert from "node:assert/strict";
import { fetchCompleteTrace } from "./agent-eval-trace.mjs";

const json = (body) => ({ ok: true, json: async () => body });

function recorder(pages) {
  const paths = [];
  const fetchPage = async (requestPath) => {
    paths.push(requestPath);
    return pages.shift() ?? { ok: false };
  };
  return { paths, fetchPage };
}

test("returns a single complete page without extra requests", async () => {
  const { paths, fetchPage } = recorder([
    json({ spans: [{ id: 1 }, { id: 2 }], totalSpans: 2, nextOffset: null }),
  ]);
  const trace = await fetchCompleteTrace(fetchPage, "run-1");
  assert.deepEqual(trace.spans, [{ id: 1 }, { id: 2 }]);
  assert.equal(trace.truncated, false);
  assert.equal(trace.fetchedPages, 1);
  assert.deepEqual(paths, ["/api/agent/runs/run-1/trace?offset=0&limit=500"]);
});

test("stitches pages until nextOffset is null", async () => {
  const { paths, fetchPage } = recorder([
    json({ spans: [{ id: 1 }], totalSpans: 3, nextOffset: 1 }),
    json({ spans: [{ id: 2 }, { id: 3 }], totalSpans: 3, nextOffset: null }),
  ]);
  const trace = await fetchCompleteTrace(fetchPage, "run-2", { pageSize: 2 });
  assert.deepEqual(trace.spans.map((span) => span.id), [1, 2, 3]);
  assert.equal(trace.truncated, false);
  assert.equal(trace.fetchedPages, 2);
  assert.deepEqual(paths, [
    "/api/agent/runs/run-2/trace?offset=0&limit=2",
    "/api/agent/runs/run-2/trace?offset=1&limit=2",
  ]);
});

test("fails closed when a later page errors", async () => {
  const { fetchPage } = recorder([
    json({ spans: [{ id: 1 }], totalSpans: 2, nextOffset: 1 }),
    { ok: false, json: async () => ({}) },
  ]);
  assert.equal(await fetchCompleteTrace(fetchPage, "run-3"), null);
});

test("fails closed when the fetched count does not match totalSpans", async () => {
  const { fetchPage } = recorder([
    json({ spans: [{ id: 1 }], totalSpans: 5, nextOffset: null }),
  ]);
  assert.equal(await fetchCompleteTrace(fetchPage, "run-4"), null);
});

test("fails closed instead of returning a partial trace at maxPages", async () => {
  const { paths, fetchPage } = recorder([
    json({ spans: [{ id: 1 }], totalSpans: 3, nextOffset: 1 }),
    json({ spans: [{ id: 2 }], totalSpans: 3, nextOffset: 2 }),
    json({ spans: [{ id: 3 }], totalSpans: 3, nextOffset: null }),
  ]);
  assert.equal(await fetchCompleteTrace(fetchPage, "run-5", { pageSize: 1, maxPages: 2 }), null);
  assert.equal(paths.length, 2);
});

test("fails closed when the response has no pagination metadata", async () => {
  const { fetchPage } = recorder([
    json({ spans: [{ id: 1 }] }),
  ]);
  assert.equal(await fetchCompleteTrace(fetchPage, "run-4b"), null);
});

test("returns null for an empty or missing trace", async () => {
  assert.equal(await fetchCompleteTrace(async () => json({ spans: [], totalSpans: 0 }), "run-6"), null);
  assert.equal(await fetchCompleteTrace(async () => null, "run-6"), null);
  assert.equal(await fetchCompleteTrace(async () => { throw new Error("offline"); }, "run-6"), null);
});

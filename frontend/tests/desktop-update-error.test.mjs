import assert from "node:assert/strict";
import test from "node:test";

import { describeDesktopUpdateError } from "../src/lib/desktop-update-error.ts";

test("legacy private-feed errors never expose response cookies", () => {
  const result = describeDesktopUpdateError(
    '404 "method: GET url: https://github.com/isCharles/project-ardor/releases.atom" Headers: { "set-cookie": "session-secret" }',
  );
  assert.equal(result.kind, "legacy-feed");
  assert.match(result.detail, /releases\.atom/);
  assert.doesNotMatch(result.detail, /set-cookie|session-secret|Headers/i);
});

test("unknown long errors have bounded detail", () => {
  const result = describeDesktopUpdateError("unexpected ".repeat(200));
  assert.equal(result.kind, "other");
  assert.ok(result.detail.length <= 801);
});

test("credentials are redacted even without a response-header block", () => {
  const result = describeDesktopUpdateError("fetch failed with github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ12345");
  assert.equal(result.kind, "network");
  assert.doesNotMatch(result.detail, /github_pat_/);
});

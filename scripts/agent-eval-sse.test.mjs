import { test } from "node:test";
import assert from "node:assert/strict";
import { SseParser } from "./agent-eval-sse.mjs";

test("handles every byte boundary, UTF-8 and CRLF", () => {
  const bytes = Buffer.from(': heartbeat\r\ndata: {"type":"done","content":"完成"}\r\n\r\n');
  for (let split = 0; split <= bytes.length; split++) {
    const events = []; const parser = new SseParser((value) => events.push(JSON.parse(value)));
    const decoder = new TextDecoder();
    parser.push(decoder.decode(bytes.subarray(0, split), { stream: true }));
    parser.push(decoder.decode(bytes.subarray(split), { stream: true }));
    parser.push(decoder.decode(), true);
    assert.deepEqual(events, [{ type: "done", content: "完成" }], `split ${split}`);
  }
});
test("preserves multiline data, emits multiple events, drops unfinished event", () => {
  const events = []; const parser = new SseParser((value) => events.push(value));
  parser.push("data: one\ndata: two\n\ndata: next\n\ndata: incomplete", true);
  assert.deepEqual(events, ["one\ntwo", "next"]);
});

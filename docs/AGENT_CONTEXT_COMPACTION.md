# Agent Context Compaction

## Why

The previous prompt builder used `MessageWindowChatMemory.withMaxMessages(40)`. That is a hard
cut: older messages silently disappear, and the limit is count-based rather than token-based.
It also does not help a 100-turn multimodal conversation.

## What the industry does

Claude's compaction replaces older turns with a server-generated summary, keeps recent turns
verbatim when requested, and can trigger automatically at a token threshold. The summary is
carried forward as the first block of the next prompt. See:

- [Compaction overview](https://platform.claude.com/docs/en/build-with-claude/compaction)
- [Compaction at a token threshold](https://platform.claude.com/docs/en/build-with-claude/compaction-threshold)
- [Compaction that keeps recent turns](https://platform.claude.com/docs/en/build-with-claude/compaction-keep-recent-turns)

Codex exposes the same shape through server-side compaction in the Responses API and the
standalone `/responses/compact` endpoint. OpenAI's long-running-agent guidance is to make
compaction a default primitive, not an emergency fallback. See:

- [Shell + Skills + Compaction](https://developers.openai.com/blog/skills-shell-tips)
- [Codex Prompting Guide · Compaction](https://developers.openai.com/cookbook/examples/gpt-5/codex_prompting_guide)

Ardor cannot use either server-side implementation directly because it supports arbitrary
user-configured OpenAI-compatible and Anthropic-compatible endpoints. It therefore uses the
same *system shape* while doing the summary call itself:

```text
system prompt
+ historical assistant data containing a JSON-encoded summary of older turns
+ recent turns verbatim
+ current user message
```

## Implementation

`AgentContextCompactor`:

- loads owner-scoped visible messages;
- finds the latest durable summary and the messages it already covers;
- keeps the latest `app.agent.context.recent-messages` (default 8) verbatim;
- compacts at the configured soft input budget after accounting for system prompt, tools,
  references, history, output reserve and safety margin; an unknown model window uses the
  configured fallback rather than treating it as a known capacity;
- asks the active chat model for a structured summary;
- stores the summary in `conversation_summaries` with version, covered message ID, covered count,
  token count and model;
- never deletes raw messages; the summary only changes the prompt view;
- keeps every uncovered message verbatim if summarization is unavailable or below the trigger;
- advances coverage only through messages actually sent to the summarizer. The 12,000-character
  batch target never marks an omitted suffix covered; an oversized first message stays whole.
- rejects generated summaries over 4,000 UTF-16 code units instead of saving a truncated result.
  On rejection, previous coverage remains unchanged and uncovered messages stay in the prompt;
  the hard input fence may reject the request if that complete history still exceeds capacity.
  This prevents application-side truncation from silently dropping the end of a summary, but does
  not prove a provider returned a complete or semantically faithful summary.

On a model request that reaches the soft budget, exact duplicate read-only tool results are
reduced only when the same registered tool, exact arguments, result text, error status and
attributes occur at least three times. The first and last results stay complete; intermediate
results retain their original call IDs and explicitly refer to the first full result. Ambiguous
IDs, SDK-flagged errors, non-text results, short results and write or unregistered tools are never reduced.
Only a request copy changes: the live chat memory, durable records, tool calls and user messages
remain untouched. Request telemetry and the hard input fence see the same compacted request.
This exact-match optimization does not establish semantic relevance or make a partially different
result safe to discard.

The JSON-encoded summary is placed in a historical **assistant data message**, never interpolated
into the system instruction or presented as a new user command. Raw generated text cannot close
an XML wrapper and promote itself to system instructions. A message-count window remains on the tool-call loop,
with enough capacity for all uncovered turns plus the bounded tool loop: a token window was tried and
removed because a large tool result could evict the user message, leaving only the system
prompt and causing the next model round to hallucinate a different task. Token awareness lives
in the rolling-summary trigger, not in mutating the live tool loop. OpenAI-compatible models
use LangChain4j's local `OpenAiTokenCountEstimator`; providers without a local tokenizer use
a conservative heuristic.

Interrupted/steered user turns are restored from owner-scoped durable runs as before; they are not
lost merely because they have not produced a saved assistant exchange. Graph phases receive the same
history. Summary-model calls use the run's model listener, so their reported tokens participate in
the run ledger rather than becoming invisible extra calls. V64 adds composite conversation/owner
validation without changing V63 or deleting any stored summaries.

The raw-message cap counts only messages not yet covered by a summary; saved raw history growing
past the cap does not permanently turn on per-turn summarization. Streaming context preparation
uses the same cancellation lease as graph calls. A stopped/deadline-expired run interrupts the
wait and rechecks the active fence before saving a returned summary.

`context.load` trace attributes now include:

```text
promptChars
historyMessages
estimatedTokens
compactedMessages
summaryVersion
```

## Configuration

```yaml
app:
  agent:
    context:
      recent-messages: 8
      max-raw-messages: 24
      max-history-tokens: 200000
```

## Context comparison experiment

`ContextComparisonExperimentTests` runs the same tagged synthetic conversation through the
production `AgentContextBuilder` / `AgentContextCompactor` / `AgentContextBudgetPolicy` /
`RepeatedToolResultCompactor` pipeline in three views:

- `RAW`: every persisted message verbatim;
- `SUMMARY`: durable summary plus recent turns produced by the real compactor;
- `SUMMARY_REPEAT`: the same summary view with three identical large read results compacted in
  the current request.

Fixtures use 20, 80 and 200 user turns. Turns 1–4 carry tags for a hard constraint, a later
correction, an entity UUID and an unresolved decision. The final probe asks to schedule an
interview at a time the constraint forbids, so a missing constraint is observable as a
`violatingSideEffects` count. The summarizer is a deterministic fixture that preserves every
tag it receives; the experiment proves the pipeline and accounting, not a provider's summary
quality.

Run it with:

```bash
cd backend
mvn -q -B -Dtest=ContextComparisonExperimentTests test
```

The report is written to `backend/target/context-comparison/report.json`. It records, per mode:

- `finalPromptTokens`: the assembled final request, including the three read results;
- `conversationTokens`: every regular turn prompt plus the final request;
- `summaryOverheadTokens`: summarizer prompt and output tokens, including the first truncated
  attempt and its retry;
- `totalTokens`: `conversationTokens + summaryOverheadTokens`;
- `retainedFacts` / `totalFacts` and `missingTags` for the four tagged facts;
- `violatingSideEffects` from the deterministic probe policy;
- `summaryLatencyMs`: virtual fixture time, not a provider latency measurement;
- `overSoftBudget`, `windowKnown`, `windowTokens`, `hardInputTokens`, and a separate projection
  against a known 32,768-token window.

Deterministic sample from the current fixture (approximate char/2 estimator):

| turns | mode | finalPromptTokens | conversationTokens | summaryOverheadTokens | totalTokens | summaryCalls |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 20 | RAW | 24,327 | 196,474 | 0 | 196,474 | 0 |
| 20 | SUMMARY | 15,159 | 165,394 | 14,998 | 180,392 | 3 |
| 20 | SUMMARY_REPEAT | 13,092 | 163,327 | 14,998 | 178,325 | 3 |
| 80 | RAW | 75,207 | 2,800,594 | 0 | 2,800,594 | 0 |
| 80 | SUMMARY | 18,551 | 771,050 | 61,414 | 832,464 | 11 |
| 80 | SUMMARY_REPEAT | 16,484 | 768,983 | 61,414 | 830,397 | 11 |
| 200 | RAW | 176,967 | 17,167,234 | 0 | 17,167,234 | 0 |
| 200 | SUMMARY | 19,399 | 1,981,514 | 160,098 | 2,141,612 | 28 |
| 200 | SUMMARY_REPEAT | 17,332 | 1,979,447 | 160,098 | 2,139,545 | 28 |

All modes retain 4/4 tagged facts and report 0 violating side effects. The lossy control
(a fixture summarizer that drops tags) retains 0/4 and reports 1 violating side effect, so the
retention metric is not vacuous.

The fixture model window is unknown: `windowTokens` and `hardInputTokens` stay `null`, and the
12,000-token soft threshold never acts as a hard limit. A 200-turn raw prompt is 176,967 tokens
at the probe and is still assembled in the experiment rather than reported as a failure. The
projection against a known 32,768-token window marks raw 80/200 as over-window and lists them
separately; only the 20-turn raw view fits, so only that case is used for a token-saving
comparison. The summary views fit the projected window at every size.

Two boundaries matter when reading the numbers:

- the 12,000-token budget covers history before the in-run tool results; a summary view can
  therefore assemble to ~19k tokens once three large read results are added, while still being
  below a 32k model window. This is why the window stays explicitly unknown without a
  configured capacity;
- summary latency is virtual fixture time and total tokens include the failed first attempt and
  its retry; neither is a real-provider measurement.

Real-model results, provider latency and semantic summary quality require a separate authorized
run. This experiment makes no historical-improvement or universal-lossless claim.

## Limitations

- This first version compacts **text transcript**. Images, audio and video are not yet accepted
  by `AgentMessageRequest`; multimodal assets need the separate reference-and-transcript design:
  store an asset ID plus OCR/caption/ASR text, inject the asset only for the current turn.
- Summary generation is synchronous when the threshold is crossed. A later version should run it
  in the background and swap the summary in when ready, matching Claude's background compaction.
- The batch target is not a guarantee that the model's context limit can never be exceeded. On a
  failed summary we preserve uncovered text rather than silently dropping it; an upstream context
  error must be reported honestly. Semantic summary quality is not proven by fake-model tests.
- Summaries are prompt data, not a user-editable record. They are owner-scoped and cascade-delete
  with the conversation.
- Tool observations are not currently persisted in `messages`; only final assistant answers are.
  Exact deduplication above applies to the current request's tool loop, not durable cross-turn
  evidence. Broader tool-evidence retrieval needs a separate owner-scoped evidence layer.

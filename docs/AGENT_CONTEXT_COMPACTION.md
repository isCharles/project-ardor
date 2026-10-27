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
      max-history-tokens: 12000
```

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

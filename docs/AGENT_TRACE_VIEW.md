# Agent trace developer view

## Execution entry and context

Chat exposes one conversation trace link at the right end of its title bar,
rather than repeating it beside each answer. The route retains a run ID as its
initial anchor and includes the conversation ID, preserving existing deep links.
The multi-turn overview sums recorded root-run durations; elapsed wall time
between messages is not execution time. Timing lanes stitch all recorded turns
onto a cumulative active-time axis, omitting idle gaps. Selecting a turn or a bar
changes the inspector, not the overview's scope or scale. Long histories scroll
horizontally inside the overview instead of widening the page.
Missing run timing falls back to recorded bounds within that run only; nested
model/tool spans are never summed on top of the root duration.

Conversation trace requests use bounded keyset pages: optional `before` (run ID)
and `limit` (default 30, maximum 100), with additive `nextBefore` in the response.
Each page retains chronological order. A cursor must belong to both the current
user and conversation; foreign or unknown cursors return the same not-found
response. Assistant messages are loaded only for that page's run IDs.
The UI follows older pages before publishing the complete overview, deduplicates
run IDs, and rejects repeated cursors. This removes the former 30-turn cutoff;
it does not itself establish 100-turn model reasoning or compaction quality.

Ordinary chat uses one streaming execution entry regardless of words such as
“只读”. List and Graph render the same persisted run spans; choosing a view never
selects an execution policy or another model workflow. Historical phase spans
remain readable, but new chat runs do not synthesize graph phases.

`AgentContextBuilder` assembles the system prompt, owner-scoped long-term memory,
conversation summary, recent messages, recovered interrupted turns and references
for both streaming chat and the compatibility synchronous transport. Summaries
and reference text are data, not system instructions. Compaction considers the
system prompt and expanded pending input as well as history. Its token budget is
a soft compaction target, not a provider context-limit guarantee; failed
compaction never silently drops user turns. Trace attributes expose the estimated
assembled size and whether it exceeds that target.

Production tool bindings no longer infer permission by matching verbs or module
names in user text. The model interprets the trusted conversation; account/module
grants decide which registered capabilities may run without confirmation.
Insufficient grants request confirmation for the concrete tool arguments.
Deletion remains an owner-validated proposal requiring a UI action. Ownership,
state validation, cancellation fencing and duplicate-write protection remain
independent checks. A denied confirmation is recorded as a `NOT_EXECUTED` tool
span rather than disappearing from the execution view.

The stored 0–3 custom grant contract remains compatible. Ask / Auto / Full /
Custom modes were delivered separately in #202; proposal-only adapters still
return proposal cards. See [approval modes](AGENT_APPROVAL_MODES.md).

The context indicator consumes measured prompt-assembly metadata and real
compaction events, with persistent progress/message fallback. Its app compression
target is not the model window. Unknown model capacity is shown as unknown,
without a guessed percentage; see [context usage](CONTEXT_USAGE.md) for the exact
estimate exclusions and candidate acceptance status.

The developer trace page lives at `/app/agent-runs/{requestId}`. It is an
owner-scoped, read-only view over `GET /api/agent/runs/{requestId}/trace`.

## Views

- **Flow**: an execution graph built from the root `RUN` span and its children,
  ordered by `startedAt`. Edges follow execution order; failed steps use a red
  edge. Clicking a node opens a small metadata panel.
- **Timeline**: the original chronological span list. Keep this view because it
  is easier to scan when the graph is noisy and it is the accessibility fallback.

The trace payload does not contain raw prompts, tool arguments or tool results.
Do not add those to the page; only bounded span metadata is exposed.

## Current data shape

Most runs store `LLM`, `TOOL` and `CONTEXT` spans as direct children of the root
`RUN` span. The graph therefore primarily communicates execution order, not a
deep LangGraph-style state graph. If deeper parent/child spans are added later,
`parentSpanId` is already available in the API and can drive a richer DAG layout.

## Tests / CI

`frontend/tests/agent-trace-ui.mjs` starts the production frontend against a
fully mocked trace and verifies:

- every span becomes a node;
- selecting a node shows metadata;
- switching to the timeline removes the graph nodes.

The test runs in `frontend-quality.yml`. Update the fixture and assertions when
the trace view contract changes.

## Bounded input/output previews

Developer trace nodes now include optional `inputPreview`, `outputPreview`,
`argumentsPreview` and `resultPreview` attributes. These are owner-scoped and
intentionally bounded to about 900 characters per field by `TracePreview`.
Secrets are redacted and whitespace is collapsed. They are previews, not the
full prompt, tool payload, or private chain-of-thought; the UI labels them as
truncated/redacted.

The model listener records the last user message as input context and the
assistant text (or requested tool names) as output. Graph phase spans record the
phase input and the phase conclusion. Tool spans record argument/result previews.
`THOUGHT` still means a phase summary span, never raw hidden reasoning.

## Multi-turn inspector

`/app/agent-runs/<runId>?conversation=<conversationId>` loads the whole
conversation through `GET /api/agent/conversations/{conversationId}/trace`.

The left column lists turns (user message, assistant answer, status, step count).
Selecting a turn renders that run's flow graph and the span detail inspector.
The single-run trace page exposes a "多轮轨迹" link; live and saved chat trace
links carry the conversation id automatically.

The endpoint is owner-scoped and returns only the latest 30 runs. Each run's
spans are read through the same bounded `AgentTraceStore` used by the single-run
trace; raw prompts and tool payloads remain out of the trace store.

## Graph and Trajectory modes

The trace page exposes both visualizations over the same run data:

- **Graph**: the serpentine flow graph, retained from the original developer view;
- **Trajectory**: the DSH-style turn list + span inspector, with
  `Overview / Input / Result / Schema / Metadata / Timing` tabs;
- multi-turn mode adds a conversation overview (duration, turns, model/tool
  calls, tokens), a three-lane time overview (input/context, model, tools), and
  a step list.

Tool schemas come from `GET /api/agent/tools/schemas`, generated from the same
`@Tool` / `@P` annotations used by LangChain4j. When a field was not recorded,
the UI labels it unavailable; it never invents timing, schema or usage.

## Unified trace layout

The multi-turn view no longer has separate Graph and Trajectory modes. It has one
DSH-style layout:

```text
left   conversation turns
middle step area (List / Graph toggle)
right  selected-step inspector
```

Clicking a graph node and clicking a step row both update the same right-side
inspector. The graph is scaled to the available middle-panel width, so there is
no horizontal scrollbar. The top time overview remains a projection of recorded
start/finish/duration values.

LLM steps use the assistant inspector: Overview / Preview / Raw content. The
Preview tab renders markdown; the model reasoning output is shown separately and
labelled, and never mixed into the final answer. Tool steps keep Parameters /
Result / Schema / Metadata / Timing.

## Observation, not a chat mode

There is no user-selectable Graph chat mode. The composer always sends a normal
chat request. A read-only graph runtime may be selected automatically for an
explicit read-only audit/planning request, but that is an execution policy, not a
button the user chooses while chatting.

The trace page's "Flow graph / List / Timeline / Inspector" controls only change
how the recorded run is displayed. They do not rerun, alter or select the Agent
runtime. The UI labels this as "observation only".

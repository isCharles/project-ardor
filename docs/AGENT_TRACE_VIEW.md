# Agent trace developer view

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

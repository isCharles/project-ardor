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

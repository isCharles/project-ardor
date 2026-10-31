# OpenTelemetry Technical Tracing

Ardor keeps two traces with different jobs:

| Record | Owner | Purpose | Failure impact |
| --- | --- | --- | --- |
| Product trace (`agent_runs`, `agent_trace_spans`, trace viewer) | Ardor business code in PostgreSQL | Run state, approvals, token usage, tool outcomes, billing-adjacent facts | A trace write failure is recorded but never decides the run result |
| Technical telemetry (OpenTelemetry spans) | OTel SDK + OTLP backend | Latency, parent-child shape, status, cross-thread and cross-process correlation | Never affects business results; spans are dropped if export fails |

The product trace is authoritative. OpenTelemetry mirrors the same spans and adds a standard
`traceId/spanId`; it does not replace or gate any product record. This integration is validated on a
local disposable stack only and is **not deployed to production** by this change.

## Implementation

- Spring Boot 4.1.1 owns the SDK, propagators, Micrometer-Tracing bridge and batch processor
  (`spring-boot-starter-opentelemetry`). No Java agent and no second tracing SDK are used.
- Boot's OTLP exporter is switched off (`management.tracing.export.otlp.enabled=false`), and
  `OtelExportConfiguration` registers the only exporter when `app.observability.otel.enabled=true`.
  Otherwise spans would be exported twice.
- `TechnicalTracing` / `TechnicalRunTrace` are the only Ardor-to-OTel boundary. When tracing is
  disabled every method is a no-op.
- `GenAiSemconv` maps business facts with an explicit allowlist. It is pinned to semantic
  conventions **1.41.1** (the version shipped by Spring Boot 4.1.1). The GenAI conventions are still
  development-stage; review this file when upgrading the convention version.
- `V95__add_technical_trace_links.sql` adds `otel_trace_id`/`otel_span_id` links to
  `agent_trace_spans`, `agent_runs.otel_trace_id`, and `traceparent` to `ai_jobs` and
  `agent_message_queue`. Migrations are additive; no existing migration is rewritten.

### Span shape

```
http post /api/agent/messages/stream        (Boot HTTP server span)
  secured request                           (Spring Security)
    agent.run                               gen_ai.operation.name=invoke_agent
      context.load
        memory.context_load
      llm.call.1                            gen_ai.operation.name=chat
      delete_calendar_task                  gen_ai.operation.name=execute_tool
```

```
http post /api/agent/queue                  (enqueue request)
  secured request
    queue.execute                           ardor.queue.wait_ms=<enqueue to claim>
      agent.run
```

```
http post /api/job-discovery/runs           (enqueue request)
  secured request
    job.execute                             ardor.job.type / ardor.job.attempt / ardor.queue.wait_ms
```

The durable worker spans are children of the enqueue request because the W3C `traceparent` is
persisted and restored explicitly. Queue wait is a span attribute, not idle time on the run span.
Streaming model callbacks run on transport threads; child spans are looked up by business span id
rather than assuming a ThreadLocal survives.

### Attribute namespaces

- GenAI: `gen_ai.operation.name`, `gen_ai.provider.name`, `gen_ai.request.model`,
  `gen_ai.response.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
  `gen_ai.usage.cache_read.input_tokens`, `gen_ai.usage.cache_creation.input_tokens`,
  `gen_ai.response.finish_reasons`, `gen_ai.tool.name`, `gen_ai.tool.call.id`,
  `gen_ai.agent.name`, `error.type`.
- Ardor: `ardor.run.id`, `ardor.conversation.id`, `ardor.span.kind`, `ardor.span.status`,
  `ardor.queue.id`, `ardor.queue.wait_ms`, `ardor.job.id`, `ardor.job.type`,
  `ardor.job.attempt`, `ardor.tool.executed`, `ardor.tool.proposal`, plus bounded purpose and
  timing fields.

`ardor.tool.executed=true` with `ardor.tool.proposal=true` means the tool call returned a
confirmation proposal; the write itself did not run. A blocked or denied call is recorded as
`ardor.span.status=NOT_EXECUTED` and is not set to ERROR because it is a business decision, not
infrastructure failure.

## Context propagation

| Boundary | Mechanism |
| --- | --- |
| HTTP entry | Boot server span; Spring Security and other framework spans stay as parents |
| SSE request thread | `TechnicalTracing.wrap` captures the request context before the virtual thread starts |
| Model streaming callbacks | Parent is resolved from the persisted business span id; MDC correlation is re-established in completion/error callbacks |
| Durable message queue | `agent_message_queue.traceparent` (W3C) written at enqueue, attached by the worker |
| AI jobs | `ai_jobs.traceparent` written at enqueue, attached by `AiJobWorker`, `job.execute` made current |
| Cancellation | Open child spans are ended as errors so a canceled run never leaves a never-exported open span |

Sampling is parent-based (`management.tracing.sampling.probability`, `ARDOR_OTEL_SAMPLE_RATE`,
default `1.0`). `include-unsampled=false` means unsampled spans are not exported. Business records
are written regardless of the sampling decision.

## Export behavior

- Boot's batch processor bounds the queue (default 2048) and batch size (512), flushes every 5 s and
  applies a 30 s export timeout. The exporter itself uses the configured connect/read timeouts.
- Export runs off the business thread. If the collector is unavailable the run still completes; spans
  are dropped after the bounded queue fills.
- `ardor.otel.export.failures` and `ardor.otel.export.spans` are Micrometer counters. A failure logs a
  rate-limited WARN without printing prompts, tool bodies or credentials.
- `OtelExportConfiguration` accepts only `http`/`https` endpoints with a host and rejects userinfo.
  Keep credentials in headers (`app.observability.otel.headers`), not in the endpoint URL.
- Exporter noise is filtered: scheduled poll root traces and Redis client spans are not exported.
  Framework security/HTTP spans are kept because the run span hangs below them.

## Privacy

- Prompts, model replies, tool arguments, tool results, resumes and uploaded content never reach the
  technical backend. Preview fields accepted by the product trace are dropped by the allowlist and
  covered by a unit test.
- Span names are code-defined (`agent.run`, `llm.call.N`, tool names) or product job types; no user
  text is used as a name.
- Trace/span ids and run/job ids appear on spans and in logs, never as metric tags. No
  high-cardinality label was added.
- Logs now carry `traceId` and `spanId` next to the existing `runId`/`userId`, so a failing run can be
  found from either trace system. MDC is restored explicitly on model callback threads.

## Local trace backend

One backend is enough for local work; this repository uses Jaeger all-in-one with persistent Badger
storage (Tempo or a second backend would duplicate storage and maintenance).

```bash
docker compose -f docker-compose.observability.yml up -d
# enable the application side (restart the backend afterwards)
ARDOR_OTEL_ENABLED=true
ARDOR_OTEL_ENDPOINT=http://127.0.0.1:4318/v1/traces
# stop / remove
docker compose -f docker-compose.observability.yml stop
docker compose -f docker-compose.observability.yml down   # keeps the named volume
```

- UI and OTLP receiver bind to `127.0.0.1` only. Do not publish them on a public interface.
- Spans live in the named volume `ardor-jaeger-data` (Badger, on disk). `down` keeps the volume;
  `down -v` deletes trace history.
- Retention is 168 h (`BADGER_SPAN_STORE_TTL`); the product trace keeps its own retention.
- The container is capped at 512 MB and is not part of the normal application stack, so an absent
  collector cannot stop the application.
- Without the volume this backend would be in-memory only: a restart would lose spans and the UI
  would look empty. That mode is suitable only for short tests.

Query paths:

- UI: `http://127.0.0.1:16686`, service `ardor-backend`.
- Product trace API: `GET /api/agent/runs/{requestId}/trace` returns `traceId` and per-span
  `otelSpanId`; open the same trace in Jaeger.
- Useful tags: `ardor.run.id`, `ardor.job.id`, `gen_ai.tool.name`, `error.type`.

## Acceptance

`scripts/otel-acceptance.mjs` drives a disposable stack with the synthetic fake LLM (no paid model
calls) and asserts the product trace against Jaeger. It covers:

1. Successful stream: every business span has its technical span; LLM descends from the run.
2. Failed model call: `agent.run`/`llm.call.1` ERROR, `error.type=LLM_HTTP_ERROR`, business status FAILED.
3. Durable queue: `agent.run` is a child of `queue.execute`, which continues the enqueue trace; wait is recorded.
4. Cancellation: stream ends, business run terminal, open spans closed as `AGENT_RUN_CANCELLED`.
5. Two concurrent users: distinct trace ids, no cross-linked spans, foreign trace access 404.
6. Tool proposal: `delete_calendar_task` is `COMPLETED` with `ardor.tool.proposal=true`, the task is
   not deleted, and the trace does not claim an executed deletion.
7. AI job: `job.execute` continues the enqueue HTTP trace and carries job type/attempt/wait.

Verified on 2026-10-31 against a disposable PostgreSQL/Redis stack and Jaeger 1.62.0. Offline export
was verified separately: with Jaeger stopped a run still completed as `COMPLETED`,
`ardor.otel.export.failures` increased, and a new trace arrived after Jaeger restarted.

For a local disposable stack that uses loopback fake-model URLs, set
`ARDOR_ALLOW_PRIVATE_API_BASE_URLS=true` only in that throwaway environment.

## Rollback

1. Set `ARDOR_OTEL_ENABLED=false` (or remove the OTel environment block) and restart the backend.
   No OTel spans are created; the product trace, run state, approvals and jobs are unchanged.
2. Stop the local backend: `docker compose -f docker-compose.observability.yml stop`.
3. The V95 columns are additive and can stay; they are null when tracing is off. No migration
   rollback is required.
4. If the collector is down but tracing stays enabled, the application continues and drops spans;
   restart the collector to resume export.

## Known limitations

- This change is validated locally only; no production collector, retention policy or dashboards
  are deployed.
- AI job retry/backoff is covered by existing unit tests, not by this end-to-end acceptance: job
  discovery completes even when no search provider is configured, so no retry is produced.
- The exporter has no disk spool. A long collector outage loses spans once the in-memory queue fills.
- A process crash can leave the last partial batch unexported; the product trace and the queue/job
  recovery paths still decide the business outcome.
- Only the Ardor Agent, durable queue, AI jobs and model gateway are instrumented. Voice WebSocket,
  outbound search/fetch clients and the desktop shell are not.
- An operator-configured collector URL appears in the exporter's OkHttp thread name in console logs
  (origin/path only). It is not user content and credentials are rejected in URLs.

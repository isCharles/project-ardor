# Agent Observability

Logs, metrics and traces for Agent runs. All instrumentation preserves user
isolation: every metric and log line is scoped by `userId`, and no prompt body,
tool argument or model reply is written to logs or metric tags.

## Structured logging

The backend uses SLF4J + Logback with a correlation-aware console pattern:

```
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %level [%thread] %logger{36} — %msg — runId=%X{runId:-} userId=%X{userId:-}
```

`RequestCorrelationFilter` populates MDC at the HTTP boundary:

| MDC key     | Source                                              |
|-------------|-----------------------------------------------------|
| `requestId` | `X-Request-Id` header or a random UUID per request  |
| `userId`    | Spring Security authentication context              |

`CareerAgentService` also sets `runId` and `userId` in MDC for the duration of
each agent run (both sync and streaming paths). All MDC entries are cleared in
`finally` blocks to prevent leakage across pooled threads.

## Micrometer metrics

All metrics follow the naming convention `ardor.agent.<domain>.<metric>`.
The Prometheus registry is on the classpath and the `/actuator/prometheus`
endpoint is exposed alongside `/actuator/metrics`.

### Run lifecycle

| Metric                        | Type    | Tags           | Description                                |
|-------------------------------|---------|----------------|--------------------------------------------|
| `ardor.agent.run.duration`    | Timer   | —              | Wall-clock duration of each agent run      |
| `ardor.agent.run.active`      | Gauge   | —              | Currently executing agent runs             |
| `ardor.agent.run.successes`   | Counter | —              | Runs that completed without error          |
| `ardor.agent.run.errors`      | Counter | —              | Runs that ended in error                   |

### LLM calls

| Metric                        | Type    | Tags           | Description                                |
|-------------------------------|---------|----------------|--------------------------------------------|
| `ardor.agent.llm.call.duration`| Timer  | `model`        | Per-call latency by model                  |
| `ardor.agent.llm.tokens.input` | Counter| `model`        | Input tokens consumed                      |
| `ardor.agent.llm.tokens.output`| Counter| `model`        | Output tokens produced                     |
| `ardor.agent.llm.errors`       | Counter| `model`        | LLM calls that ended in error              |

### Tool execution

| Metric                          | Type    | Tags         | Description                                |
|---------------------------------|---------|--------------|--------------------------------------------|
| `ardor.agent.tool.call.duration`| Timer   | `tool`       | Per-tool execution time                    |
| `ardor.agent.tool.call.errors`  | Counter | `tool`       | Tool executions that failed                |

### Streaming

| Metric                        | Type    | Tags           | Description                                |
|-------------------------------|---------|----------------|--------------------------------------------|
| `ardor.agent.stream.ttft`     | Timer   | —              | Time from run start to first streamed token|

### Context compaction

| Metric                                    | Type    | Tags | Description                                        |
|-------------------------------------------|---------|------|----------------------------------------------------|
| `ardor.agent.context.compactions`         | Counter | —    | Context summaries written for agent conversations  |
| `ardor.agent.context.compaction.failures` | Counter | —    | Summary attempts that produced no usable summary   |

These two counters show how often a conversation rewrites its summary. A
conversation that sits near the soft budget rewrites on nearly every turn
(see `docs/CONTEXT_USAGE.md`), which is a cost signal rather than an error.

### Existing

| Metric                          | Type    | Description                                    |
|---------------------------------|---------|------------------------------------------------|
| `ardor.agent.trace.write.failures`| Counter| Trace writes dropped without failing the run  |

## Instrumentation points

`AgentMetrics` is injected into:

- `CareerAgentService` — run start/end, TTFT, tool duration
- `AgentModelTraceListener` — LLM call duration, tokens, errors
- `AgentContextCompactor` — summary written/failed counters

Both sync (`chatInternal`) and streaming (`chatStream`) paths are instrumented.
Metrics recording is fire-and-forget: a `RuntimeException` from the meter
registry is caught and ignored so that observability never breaks a run.

## Trace correlation

The existing custom trace system (`AgentRunTracer`, `AgentTraceStore`) persists
per-run spans to PostgreSQL. Trace spans carry `userId`, `runId`, `model`,
token counts and bounded attributes. The trace viewer at
`/app/agent-runs/[requestId]` renders spans as a flow graph, timeline and
trajectory.

Log lines emitted during a run carry the same `runId` in MDC, so a specific
run can be traced from the Prometheus metrics → log lines → persisted spans.

## What this does not cover

- **Semantic correctness**: metrics confirm that tools ran and LLM calls
  completed; they do not prove the answer was correct.
- **Business error detection**: a tool that returns an error message as normal
  text (without throwing) is still recorded as `COMPLETED`.
- **OTLP export**: the trace system is PostgreSQL-backed, not OpenTelemetry.
  OTLP export to Jaeger/Tempo/Grafana is a future consideration.
- **Evaluation persistence**: `scripts/agent-eval.mjs` reads trace data but
  results are ephemeral JSON; historical eval dashboards are not yet built.

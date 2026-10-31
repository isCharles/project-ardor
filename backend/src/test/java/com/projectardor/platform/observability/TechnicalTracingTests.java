package com.projectardor.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.projectardor.assistant.execution.service.AgentRunTracer;
import com.projectardor.assistant.execution.service.AgentTraceStore;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class TechnicalTracingTests {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private TechnicalTracing tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracing = new TechnicalTracing(sdk, true);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        MDC.clear();
    }

    @Test
    void mirrorsBusinessParentageAndDropsPreviewAttributes() {
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        var run = tracing.startRun("agent.run", runId, conversationId, "model-a", "OPENAI_COMPATIBLE",
                "v1", Map.of("originRun", runId.toString(), "inputPreview", "secret prompt"));
        UUID rootBusiness = UUID.randomUUID();
        run.attachRoot(rootBusiness);
        UUID llmBusiness = UUID.randomUUID();
        run.startChild(llmBusiness, rootBusiness, "LLM", "llm.call.1", "model-a", null,
                Map.of("purpose", "ASSISTANT", "inputPreview", "secret prompt",
                        "requestedTools", List.of("list_resumes")));
        run.finishChild(llmBusiness, "COMPLETED", null, "model-a", 120, 40, null, null,
                Map.of("finishReason", "STOP", "requestDurationMs", 1_234L, "firstContentMs", 200L,
                        "timingSource", "STREAM", "requestedTools", List.of("list_resumes")));
        UUID toolBusiness = UUID.randomUUID();
        run.startChild(toolBusiness, llmBusiness, "TOOL", "list_resumes", null, null,
                Map.of("tool", "list_resumes", "toolCallId", "call-1", "argumentsPreview", "secret args"));
        run.finishChild(toolBusiness, "COMPLETED", null, null, null, null, null, null,
                Map.of("executed", true, "proposal", true));
        run.finish("COMPLETED", null);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData root = named(spans, "agent.run");
        SpanData llm = named(spans, "llm.call.1");
        SpanData tool = named(spans, "list_resumes");
        assertThat(llm.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(llm.getParentSpanContext().getSpanId()).isEqualTo(root.getSpanId());
        assertThat(tool.getParentSpanContext().getSpanId()).isEqualTo(llm.getSpanId());
        assertThat(root.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                GenAiSemconv.RUN_ID))).isEqualTo(runId.toString());
        assertThat(llm.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                GenAiSemconv.OPERATION_NAME))).isEqualTo("chat");
        assertThat(llm.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey(
                GenAiSemconv.INPUT_TOKENS))).isEqualTo(120L);
        assertThat(tool.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                GenAiSemconv.TOOL_NAME))).isEqualTo("list_resumes");
        assertThat(tool.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey(
                GenAiSemconv.TOOL_EXECUTED))).isTrue();
        assertThat(tool.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey(
                GenAiSemconv.TOOL_PROPOSAL))).isTrue();
        assertThat(llm.getAttributes().get(AttributeKey.longKey("ardor.llm.request_duration_ms"))).isEqualTo(1_234L);
        assertThat(llm.getAttributes().get(AttributeKey.longKey("ardor.llm.first_content_ms"))).isEqualTo(200L);
        assertThat(llm.getAttributes().get(AttributeKey.stringKey("ardor.llm.timing_source"))).isEqualTo("STREAM");
        assertThat(llm.getAttributes().get(AttributeKey.stringArrayKey(GenAiSemconv.REQUESTED_TOOLS)))
                .containsExactly("list_resumes");
        assertThat(String.valueOf(root.getAttributes().asMap()))
                .doesNotContain("secret prompt").doesNotContain("inputPreview");
        assertThat(String.valueOf(llm.getAttributes().asMap())).doesNotContain("secret prompt");
        assertThat(String.valueOf(tool.getAttributes().asMap())).doesNotContain("secret args");
    }

    @Test
    void cancellationEndsOpenChildrenAsErrors() {
        var run = tracing.startRun("agent.run", UUID.randomUUID(), null, "model-a", null, "v1", Map.of());
        UUID rootBusiness = UUID.randomUUID();
        run.attachRoot(rootBusiness);
        UUID contextBusiness = UUID.randomUUID();
        run.startChild(contextBusiness, rootBusiness, "CONTEXT", "context.load", "model-a", null, Map.of());
        run.finish("FAILED", "AGENT_RUN_CANCELLED");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData context = named(spans, "context.load");
        SpanData root = named(spans, "agent.run");
        assertThat(context.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(context.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                GenAiSemconv.ERROR_TYPE))).isEqualTo("AGENT_RUN_CANCELLED");
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void traceparentRoundTripsAcrossAnExplicitlyCapturedContext() {
        Span parent = provider.get("test").spanBuilder("http.server").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            String traceparent = tracing.currentTraceparent();
            assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
            try (var attachment = tracing.attach(traceparent)) {
                assertThat(MDC.get("traceId")).isEqualTo(parent.getSpanContext().getTraceId());
                var run = tracing.startRun("agent.run", UUID.randomUUID(), null, "model-a", null, "v1", Map.of());
                run.attachRoot(UUID.randomUUID());
                run.finish("COMPLETED", null);
            }
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            parent.end();
        }
        SpanData child = named(exporter.getFinishedSpanItems(), "agent.run");
        assertThat(child.getParentSpanContext().getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }

    @Test
    void invalidAndZeroTraceparentsAreIgnoredInsteadOfFailingTheQueuedWork() {
        assertThat(tracing.extract("not-a-traceparent")).isNull();
        assertThat(tracing.extract("00-00000000000000000000000000000000-0000000000000000-01")).isNull();
        try (var attachment = tracing.attach("not-a-traceparent")) {
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    @Test
    void aToolChildCarriesTheParentIntoWorkItEnqueues() {
        var run = tracing.startRun("agent.run", UUID.randomUUID(), null, "model-a", null, "v1", Map.of());
        UUID rootBusiness = UUID.randomUUID();
        run.attachRoot(rootBusiness);
        UUID toolBusiness = UUID.randomUUID();
        run.startChild(toolBusiness, rootBusiness, "TOOL", "discover_jobs", null, null,
                Map.of("tool", "discover_jobs"));
        String traceparent;
        try (var attached = run.attachChild(toolBusiness)) {
            traceparent = tracing.currentTraceparent();
            assertThat(MDC.get("spanId")).isEqualTo(run.spanIdOf(toolBusiness));
        }
        assertThat(traceparent).isEqualTo(
                "00-" + run.traceId() + "-" + run.spanIdOf(toolBusiness) + "-01");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        run.finish("COMPLETED", null);
    }

    @Test
    void aChildRegisteredAfterTerminationNeverOutlivesTheRoot() {
        var run = tracing.startRun("agent.run", UUID.randomUUID(), null, "model-a", null, "v1", Map.of());
        UUID rootBusiness = UUID.randomUUID();
        run.attachRoot(rootBusiness);
        run.finish("FAILED", "AGENT_RUN_CANCELLED");
        UUID late = UUID.randomUUID();
        run.startChild(late, rootBusiness, "TOOL", "late.tool", null, null, Map.of("tool", "late.tool"));
        assertThat(run.spanIdOf(late)).isNull();
        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName).doesNotContain("late.tool");
    }

    @Test
    void agentRunTracerPassesOtelIdsToTheProductStore() {
        AgentTraceStore store = mock(AgentTraceStore.class);
        UUID user = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID rootBusiness = UUID.randomUUID();
        when(store.startSpan(eq(user), eq(runId), isNull(), eq("RUN"), eq("agent.run"),
                anyMap(), anyString(), anyString())).thenReturn(rootBusiness);
        AgentRunTracer tracer = AgentRunTracer.begin(store, user, runId, null, "model-a", "v1", null, tracing);
        tracer.complete();

        verify(store).linkRunTrace(eq(user), eq(runId), anyString());
        SpanData root = named(exporter.getFinishedSpanItems(), "agent.run");
        assertThat(root.getSpanId()).isNotBlank();
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void countingExporterObservesFailuresWithoutThrowing() {
        SpanExporter failing = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new IllegalStateException("collector down");
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        var registry = new SimpleMeterRegistry();
        var counting = new CountingSpanExporter(failing, registry);
        Span span = provider.get("com.projectardor.technical").spanBuilder("agent.run").startSpan();
        span.end();
        assertThat(counting.export(exporter.getFinishedSpanItems()).isSuccess()).isFalse();
        assertThat(registry.get("ardor.otel.export.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void endpointValidationRejectsCredentialsAndNonHttpSchemes() {
        assertThatThrownBy(() -> CountingSpanExporter.validateEndpoint("file:///tmp/traces"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountingSpanExporter.validateEndpoint("http://user:pass@collector/v1/traces"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountingSpanExporter.validateEndpoint("not a uri"))
                .isInstanceOf(IllegalArgumentException.class);
        CountingSpanExporter.validateEndpoint("http://127.0.0.1:4318/v1/traces");
    }

    @Test
    void exporterDropsFrameworkPollAndCacheLeavesButKeepsTheRequestAndAgentSpans() {
        Tracer spring = provider.get("org.springframework.boot");
        Span poll = spring.spanBuilder("task agentMessageQueueWorker.poll").startSpan();
        poll.end();
        Span redis = spring.spanBuilder("hgetall").setSpanKind(SpanKind.CLIENT)
                .setAttribute(AttributeKey.stringKey("db.system"), "redis").startSpan();
        redis.end();
        Span http = spring.spanBuilder("http post /api/agent/messages/stream").setSpanKind(SpanKind.SERVER).startSpan();
        http.end();
        Span agent = provider.get("com.projectardor.technical").spanBuilder("agent.run").startSpan();
        agent.end();

        List<SpanData> finished = exporter.getFinishedSpanItems();
        var recorded = new CopyOnWriteArrayList<SpanData>();
        SpanExporter recording = new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) {
                recorded.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        new CountingSpanExporter(recording, new SimpleMeterRegistry()).export(finished);

        assertThat(recorded).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("http post /api/agent/messages/stream", "agent.run");
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> name.equals(span.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("missing span " + name + " in " + spans));
    }
}

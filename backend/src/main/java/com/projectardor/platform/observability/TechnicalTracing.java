package com.projectardor.platform.observability;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Boundary between Ardor's product trace and the OpenTelemetry SDK.
 *
 * <p>Business code never depends on a collector: when technical tracing is disabled every method
 * returns a no-op, and an export failure is handled by the SDK's batch processor off the business
 * thread. Context is captured and restored explicitly at async boundaries instead of assuming a
 * ThreadLocal survives a virtual thread, an executor queue or a model transport callback.</p>
 */
@Component
public class TechnicalTracing {

    private static final String INSTRUMENTATION_NAME = "com.projectardor.technical";
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final Pattern ZERO_TRACE = Pattern.compile("0{32}");
    private static final Pattern ZERO_SPAN = Pattern.compile("0{16}");

    private final Tracer tracer;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public TechnicalTracing(ObjectProvider<OpenTelemetry> openTelemetry,
            @Value("${management.opentelemetry.enabled:false}") boolean enabled) {
        this(openTelemetry.getIfAvailable(OpenTelemetry::noop), enabled);
    }

    TechnicalTracing(OpenTelemetry openTelemetry, boolean enabled) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME, "1.0");
        this.enabled = enabled;
    }

    public static TechnicalTracing disabled() {
        return new TechnicalTracing(OpenTelemetry.noop(), false);
    }

    public boolean enabled() {
        return enabled;
    }

    public TechnicalRunTrace startRun(String name, UUID runId, UUID conversationId, String model, String provider,
            String promptVersion, Map<String, Object> businessAttributes) {
        if (!enabled) return TechnicalRunTrace.disabled();
        Attributes attributes = GenAiSemconv.runAttributes(runId, conversationId, model, provider,
                promptVersion, businessAttributes);
        return TechnicalRunTrace.start(tracer, name, attributes, Context.current());
    }

    /** One model invocation outside the Agent chat path, e.g. a structured evaluation request. */
    public Span startModelCall(String model, String provider) {
        if (!enabled) return Span.getInvalid();
        Span span = tracer.spanBuilder("llm.call")
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAllAttributes(GenAiSemconv.spanAttributes("LLM", model, provider, Map.of()));
        return span;
    }

    public void finishModelCall(Span span, String responseModel, boolean success, String errorType) {
        if (span == null || !span.getSpanContext().isValid()) return;
        span.setAllAttributes(GenAiSemconv.finishAttributes(success ? "COMPLETED" : "FAILED",
                responseModel, null, null, null, null, Map.of()));
        if (success) span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
        else span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
                errorType == null ? "LLM_CALL_FAILED" : errorType);
        if (!success && errorType != null) span.setAttribute(GenAiSemconv.ERROR_TYPE, errorType);
        span.end();
    }

    /** A durable queue item's execution, with the wait between enqueue and claim made explicit. */
    public Span startQueueExecution(String queue, UUID itemId, Long waitMs) {
        if (!enabled) return Span.getInvalid();
        Span span = tracer.spanBuilder("queue.execute")
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        var attributes = Attributes.builder()
                .put(GenAiSemconv.SPAN_KIND, "QUEUE")
                .put(GenAiSemconv.QUEUE_ID, queue);
        if (itemId != null) attributes.put(GenAiSemconv.QUEUE_ID + ".item", itemId.toString());
        if (waitMs != null) attributes.put(GenAiSemconv.QUEUE_WAIT_MS, Math.max(0, waitMs));
        span.setAllAttributes(attributes.build());
        return span;
    }

    public Span startJobExecution(String jobType, UUID jobId, int attempt, Long waitMs) {
        if (!enabled) return Span.getInvalid();
        Span span = tracer.spanBuilder("job.execute")
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        var attributes = Attributes.builder()
                .put(GenAiSemconv.SPAN_KIND, "JOB")
                .put(GenAiSemconv.JOB_TYPE, jobType == null ? "" : jobType)
                .put(GenAiSemconv.JOB_ATTEMPT, Math.max(0, attempt));
        if (jobId != null) attributes.put(GenAiSemconv.JOB_ID, jobId.toString());
        if (waitMs != null) attributes.put(GenAiSemconv.QUEUE_WAIT_MS, Math.max(0, waitMs));
        span.setAllAttributes(attributes.build());
        return span;
    }

    public void finishOperation(Span span, boolean success, String errorType) {
        if (span == null || !span.getSpanContext().isValid()) return;
        if (success) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
        } else {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
                    errorType == null ? "OPERATION_FAILED" : errorType);
            span.setAttribute(GenAiSemconv.ERROR_TYPE, errorType == null ? "OPERATION_FAILED" : errorType);
        }
        span.end();
    }

    /** W3C {@code traceparent} of the active span, or null when there is nothing to persist. */
    public String currentTraceparent() {
        if (!enabled) return null;
        SpanContext context = Span.fromContext(Context.current()).getSpanContext();
        if (!context.isValid()) return null;
        String flags = context.getTraceFlags().isSampled() ? "01" : "00";
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags;
    }

    /**
     * Restores a persisted parent. Invalid or foreign-format values are ignored rather than making
     * the resumed work fail; a missing link is better than dropping a queued business task.
     */
    public Attachment attach(String traceparent) {
        if (!enabled || traceparent == null) return () -> { };
        Context parent = extract(traceparent);
        if (parent == null) return () -> { };
        SpanContext span = Span.fromContext(parent).getSpanContext();
        String previousTraceId = MDC.get("traceId");
        String previousSpanId = MDC.get("spanId");
        Scope scope = parent.makeCurrent();
        MDC.put("traceId", span.getTraceId());
        MDC.put("spanId", span.getSpanId());
        return () -> {
            restore("traceId", previousTraceId);
            restore("spanId", previousSpanId);
            scope.close();
        };
    }

    /** Scoped context restoration that does not throw a checked exception at a try-with-resources exit. */
    @FunctionalInterface
    public interface Attachment extends AutoCloseable {
        @Override
        void close();
    }

    /** Captures the current context for a new thread, without relying on ThreadLocal inheritance. */
    public Runnable wrap(Runnable task) {
        if (!enabled) return task;
        Context captured = Context.current();
        SpanContext span = Span.fromContext(captured).getSpanContext();
        return () -> {
            String previousTraceId = MDC.get("traceId");
            String previousSpanId = MDC.get("spanId");
            try (Scope ignored = captured.makeCurrent()) {
                if (span.isValid()) {
                    MDC.put("traceId", span.getTraceId());
                    MDC.put("spanId", span.getSpanId());
                }
                task.run();
            } finally {
                restore("traceId", previousTraceId);
                restore("spanId", previousSpanId);
            }
        };
    }

    Context extract(String traceparent) {
        if (traceparent == null) return null;
        var matcher = TRACEPARENT.matcher(traceparent.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        if (ZERO_TRACE.matcher(traceId).matches() || ZERO_SPAN.matcher(spanId).matches()) return null;
        TraceFlags flags = "01".equals(matcher.group(3)) ? TraceFlags.getSampled() : TraceFlags.getDefault();
        SpanContext context = SpanContext.createFromRemoteParent(traceId, spanId, flags, TraceState.getDefault());
        return context.isValid() ? Context.root().with(Span.wrap(context)) : null;
    }

    private static void restore(String key, String value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }
}

package com.projectardor.platform.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

/**
 * The OpenTelemetry mirror of one business run trace.
 *
 * <p>Business span ids are the join key: the Postgres trace and the exported trace use the same
 * parent-child shape and span names. Parent lookup is explicit, so streaming callbacks on model
 * transport threads do not depend on ThreadLocal state. Spans still open when a run is cancelled
 * are ended as errors so no partial span is silently dropped.</p>
 */
public final class TechnicalRunTrace {

    private static final String ANNOTATION_EVENT = "ardor.trace.annotation";
    private static final String BUSINESS_SPAN_ID = "ardor.business_span_id";

    private final Tracer tracer;
    private final Span root;
    private final ConcurrentHashMap<UUID, Span> spans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> open = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Serializes child registration with the terminal drain so no child outlives the root. */
    private final Object lifecycle = new Object();

    private TechnicalRunTrace(Tracer tracer, Span root) {
        this.tracer = tracer;
        this.root = root;
    }

    public static TechnicalRunTrace disabled() {
        return new TechnicalRunTrace(null, null);
    }

    static TechnicalRunTrace start(Tracer tracer, String name, Attributes attributes, Context parent) {
        Span span = tracer.spanBuilder(name).setParent(parent).setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAllAttributes(attributes);
        return new TechnicalRunTrace(tracer, span);
    }

    public boolean active() {
        return root != null && !closed.get();
    }

    public boolean started() {
        return root != null;
    }

    public String traceId() {
        return root == null ? null : root.getSpanContext().getTraceId();
    }

    public String spanId() {
        return root == null ? null : root.getSpanContext().getSpanId();
    }

    /** OTel span id for a business child, or null when the child never started. */
    public String spanIdOf(UUID businessSpanId) {
        Span span = businessSpanId == null ? null : spans.get(businessSpanId);
        return span == null ? null : span.getSpanContext().getSpanId();
    }

    /** Links the business root span UUID that the trace store generated for this run. */
    public void attachRoot(UUID businessSpanId) {
        if (!started() || businessSpanId == null) return;
        spans.put(businessSpanId, root);
    }

    /**
     * Makes a child span current for business work that leaves the model callback thread, such as
     * a tool enqueueing a durable job. Without this, the enqueue captures no traceparent even
     * though the child span itself already has the right parent.
     */
    public TechnicalTracing.Attachment attachChild(UUID businessSpanId) {
        Span span = businessSpanId == null ? null : spans.get(businessSpanId);
        if (span == null) return () -> { };
        SpanContext spanContext = span.getSpanContext();
        String previousTraceId = org.slf4j.MDC.get("traceId");
        String previousSpanId = org.slf4j.MDC.get("spanId");
        io.opentelemetry.context.Scope scope = Context.root().with(span).makeCurrent();
        org.slf4j.MDC.put("traceId", spanContext.getTraceId());
        org.slf4j.MDC.put("spanId", spanContext.getSpanId());
        return () -> {
            if (previousTraceId == null) org.slf4j.MDC.remove("traceId");
            else org.slf4j.MDC.put("traceId", previousTraceId);
            if (previousSpanId == null) org.slf4j.MDC.remove("spanId");
            else org.slf4j.MDC.put("spanId", previousSpanId);
            scope.close();
        };
    }

    public void startChild(UUID businessSpanId, UUID businessParentSpanId, String kind, String name,
            String model, String provider, Map<String, Object> businessAttributes) {
        if (businessSpanId == null) return;
        synchronized (lifecycle) {
            if (!active()) return;
            Span parent = businessParentSpanId == null ? root : spans.get(businessParentSpanId);
            Context parentContext = parent == null
                    ? Context.root().with(root)
                    : Context.root().with(Span.wrap(parent.getSpanContext()));
            Span span = tracer.spanBuilder(name).setParent(parentContext).setSpanKind(SpanKind.INTERNAL).startSpan();
            span.setAllAttributes(GenAiSemconv.spanAttributes(kind, model, provider, businessAttributes));
            spans.put(businessSpanId, span);
            open.put(businessSpanId, Boolean.TRUE);
        }
    }

    public void finishChild(UUID businessSpanId, String status, String errorCode, String responseModel,
            Integer inputTokens, Integer outputTokens, Integer cacheCreation, Integer cacheRead,
            Map<String, Object> businessAttributes) {
        if (!started() || businessSpanId == null) return;
        Span span = spans.get(businessSpanId);
        if (span == null || open.remove(businessSpanId) == null) return;
        span.setAllAttributes(GenAiSemconv.finishAttributes(status, responseModel, inputTokens, outputTokens,
                cacheCreation, cacheRead, businessAttributes));
        applyStatus(span, status, errorCode);
        span.end();
    }

    /**
     * A fact learned after its span ended, such as the size of a written summary, is attached to
     * the run span as an event. OTel cannot mutate an ended span, and the product trace remains
     * the authoritative place for these late values.
     */
    public void annotateChild(UUID businessSpanId, Map<String, Object> businessAttributes) {
        if (!active() || businessSpanId == null || businessAttributes == null || businessAttributes.isEmpty()) return;
        Attributes attributes = GenAiSemconv.annotationAttributes(businessAttributes);
        if (attributes.isEmpty()) return;
        root.addEvent(ANNOTATION_EVENT, attributes.toBuilder()
                .put(BUSINESS_SPAN_ID, businessSpanId.toString()).build());
    }

    public void finish(String status, String errorCode) {
        synchronized (lifecycle) {
            if (!started() || !closed.compareAndSet(false, true)) return;
            for (UUID id : open.keySet()) {
                Span span = spans.get(id);
                if (span != null && open.remove(id) != null) {
                    applyStatus(span, "FAILED", errorCode);
                    span.end();
                }
            }
            applyStatus(root, status, errorCode);
            root.end();
        }
    }

    private static void applyStatus(Span span, String status, String errorCode) {
        if ("COMPLETED".equals(status)) {
            span.setStatus(StatusCode.OK);
            return;
        }
        if ("RUNNING".equals(status) || "NOT_EXECUTED".equals(status)) return;
        String description = errorCode == null || errorCode.isBlank() ? status : errorCode;
        span.setStatus(StatusCode.ERROR, description);
        if (errorCode != null && !errorCode.isBlank()) {
            span.setAttribute(GenAiSemconv.ERROR_TYPE, errorCode);
        }
    }
}

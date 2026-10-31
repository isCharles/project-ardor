package com.projectardor.platform.observability;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Counts OTLP export outcomes without ever being on the business path. The SDK's batch processor
 * owns the bounded queue and worker thread; this wrapper observes each batch result.
 *
 * <p>Queue-full drops happen inside the SDK before this exporter is called, so this class cannot
 * count them. The SDK logs those drops; see docs/OPENTELEMETRY.md.</p>
 */
final class CountingSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(CountingSpanExporter.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final String SPRING_SCOPE = "org.springframework.boot";
    private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");

    private final SpanExporter delegate;
    private final Counter failures;
    private final Counter exportedSpans;
    private final AtomicLong lastWarning = new AtomicLong();

    CountingSpanExporter(SpanExporter delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.failures = meters == null ? null : Counter.builder("ardor.otel.export.failures")
                .description("OTLP span batches that the collector or transport rejected")
                .register(meters);
        this.exportedSpans = meters == null ? null : Counter.builder("ardor.otel.export.spans")
                .description("Spans accepted by the OTLP collector")
                .register(meters);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        // Spring observes every @Scheduled poll. The agent queue polls once a second, so exporting
        // those root spans would create a steady stream of business-less traces and hide real runs.
        List<SpanData> exportable = spans.stream()
                .filter(span -> !isScheduledRoot(span) && !isFrameworkNoise(span)).toList();
        if (exportable.isEmpty()) return CompletableResultCode.ofSuccess();
        CompletableResultCode result;
        try {
            result = delegate.export(exportable);
        } catch (RuntimeException failure) {
            recordFailure(failure);
            return CompletableResultCode.ofFailure();
        }
        result.whenComplete(() -> {
            if (result.isSuccess()) {
                if (exportedSpans != null) exportedSpans.increment(exportable.size());
            } else {
                recordFailure(null);
            }
        });
        return result;
    }

    private static boolean isScheduledRoot(SpanData span) {
        if (span.getParentSpanContext().isValid()) return false;
        String name = span.getName();
        return name.startsWith("task ") || name.startsWith("scheduled ");
    }

    /**
     * Redis command spans are high-volume leaves beneath the HTTP request; their metrics remain,
     * but exporting them would bury the model/tool path and copy cache key context into the
     * collector. Security/filter spans stay because they are the current parents of the Agent
     * run when security is active; dropping them would orphan that child in the trace UI.
     */
    private static boolean isFrameworkNoise(SpanData span) {
        if (!SPRING_SCOPE.equals(span.getInstrumentationScopeInfo().getName())) return false;
        String name = span.getName();
        if (name.startsWith("task ") || name.startsWith("scheduled ")) return true;
        return span.getKind() == SpanKind.CLIENT && "redis".equalsIgnoreCase(span.getAttributes().get(DB_SYSTEM));
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private void recordFailure(RuntimeException failure) {
        if (failures != null) failures.increment();
        long now = System.nanoTime();
        long previous = lastWarning.get();
        if (now - previous >= WARNING_INTERVAL_NANOS && lastWarning.compareAndSet(previous, now)) {
            log.warn("OTLP span export failed; traces may be missing while business execution continues{}",
                    failure == null ? "" : " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("OTLP endpoint is required when technical tracing is enabled");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("OTLP endpoint is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("OTLP endpoint must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("OTLP endpoint must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("OTLP endpoint must not embed credentials");
        }
    }
}

package com.projectardor.platform.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Replaces the auto-configured OTLP exporter with a counted one.
 *
 * <p>{@code management.tracing.export.otlp.enabled=false} keeps Boot from creating a second
 * exporter; this bean is the only one collected by the SDK's batch processor.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtelExportProperties.class)
@ConditionalOnProperty(name = "app.observability.otel.enabled", havingValue = "true")
public class OtelExportConfiguration {

    @Bean(destroyMethod = "")
    public SpanExporter ardorOtlpSpanExporter(OtelExportProperties properties, MeterRegistry meters) {
        CountingSpanExporter.validateEndpoint(properties.getEndpoint());
        SpanExporter delegate = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.getEndpoint().strip())
                .setTimeout(properties.getTimeout())
                .setConnectTimeout(properties.getConnectTimeout())
                .setHeaders(properties::getHeaders)
                .build();
        return new CountingSpanExporter(delegate, meters);
    }
}

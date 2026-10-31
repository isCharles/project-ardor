package com.projectardor.platform.observability;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-owned OTLP export settings. The endpoint is validated once at startup and never
 * logged, because it may carry deployment-specific hostnames.
 */
@ConfigurationProperties("app.observability.otel")
public class OtelExportProperties {

    private boolean enabled;
    private String endpoint = "http://127.0.0.1:4318/v1/traces";
    private Duration timeout = Duration.ofSeconds(10);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Map<String, String> headers = Map.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers == null ? Map.of() : headers; }
}

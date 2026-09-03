package com.projectardor.common.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExternalBaseUrlPolicy {

    private static final int DNS_RESOLUTION_ATTEMPTS = 3;
    private static final long DNS_RETRY_DELAY_MS = 300;

    private final boolean allowPrivateAddresses;

    public ExternalBaseUrlPolicy(
            @Value("${app.security.allow-private-api-base-urls:false}") boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public String normalizeAndValidate(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        String value = rawBaseUrl.strip();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw invalidUrl();
        }
        validatePublicHost(uri.getHost());
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public void validatePublicHost(String host) {
        if (allowPrivateAddresses) return;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!normalized.contains(".")
                || normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")
                || normalized.endsWith(".internal")) {
            throw privateAddress();
        }
        InetAddress[] addresses = resolveAddresses(host);
        for (InetAddress address : addresses) {
            byte[] bytes = address.getAddress();
            boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || uniqueLocalIpv6) {
                throw privateAddress();
            }
        }
    }

    private InetAddress[] resolveAddresses(String host) {
        UnknownHostException lastFailure = null;
        for (int attempt = 1; attempt <= DNS_RESOLUTION_ATTEMPTS; attempt++) {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException exception) {
                lastFailure = exception;
                if (attempt < DNS_RESOLUTION_ATTEMPTS) pauseBeforeDnsRetry(attempt);
            }
        }
        throw new ExternalHostResolutionException("Base URL 的主机暂时无法解析，请稍后重试", lastFailure);
    }

    private void pauseBeforeDnsRetry(int attempt) {
        try {
            Thread.sleep(DNS_RETRY_DELAY_MS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalHostResolutionException("Base URL 主机解析被中断，请重试", exception);
        }
    }

    private IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("Base URL 必须是有效的 HTTP 或 HTTPS 公共地址");
    }

    private IllegalArgumentException privateAddress() {
        return new IllegalArgumentException("Base URL 不允许指向本机、局域网、容器内部或链路本地地址");
    }
}

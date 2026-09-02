package com.projectardor.common.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new ProxyAwareRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            requestFactory.setProxy(new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyHost.strip(), proxyPort)));
        }
        return RestClient.builder().requestFactory(requestFactory);
    }

    private static final class ProxyAwareRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
            return super.openConnection(url, bypassProxy(url.getHost()) ? Proxy.NO_PROXY : proxy);
        }

        private boolean bypassProxy(String host) {
            if (host == null || host.isBlank()) return false;
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("localhost") || normalized.equals("127.0.0.1")
                    || normalized.equals("::1") || normalized.endsWith(".internal")
                    || !normalized.contains(".")) return true;
            if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) return true;
            if (normalized.startsWith("172.")) {
                String[] segments = normalized.split("\\.");
                if (segments.length > 1) {
                    try {
                        int second = Integer.parseInt(segments[1]);
                        return second >= 16 && second <= 31;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }
            }
            return false;
        }
    }
}

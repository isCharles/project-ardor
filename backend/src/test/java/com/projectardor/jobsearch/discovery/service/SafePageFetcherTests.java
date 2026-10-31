package com.projectardor.jobsearch.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SafePageFetcherTests {

    private static final SafePageFetcher.UrlGuard PUBLIC = url -> url;

    @Test void redirectsToLocalhostAreRejectedBeforeTheSecondRequest() {
        AtomicInteger requests = new AtomicInteger();
        SafePageFetcher.UrlGuard guard = url -> {
            URI uri = URI.create(url);
            if ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())) {
                throw new IllegalArgumentException("blocked");
            }
            return url;
        };
        var fetcher = new SafePageFetcher(guard, uri -> {
            requests.incrementAndGet();
            return new SafePageFetcher.Response(302, "http://127.0.0.1:8080/admin", "text/html", "");
        });
        assertThatThrownBy(() -> fetcher.fetch("https://jobs.example.com/role"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许访问");
        assertThat(requests).hasValue(1);
    }

    @Test void followsValidatedRelativeRedirectsAndReturnsTheFinalUrl() {
        AtomicInteger requests = new AtomicInteger();
        var fetcher = new SafePageFetcher(PUBLIC, uri -> {
            if (requests.incrementAndGet() == 1) {
                return new SafePageFetcher.Response(302, "/careers/backend", "text/html", "");
            }
            return new SafePageFetcher.Response(200, null, "text/html", "<title>Backend</title>");
        });
        var page = fetcher.fetch("https://jobs.example.com/role");
        assertThat(page.finalUrl()).isEqualTo("https://jobs.example.com/careers/backend");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.linkStatus()).isEqualTo(SafePageFetcher.STATUS_OPEN);
        assertThat(requests).hasValue(2);
    }

    @Test void redirectLoopsStopAsUnknownInsteadOfSpinning() {
        AtomicInteger requests = new AtomicInteger();
        var fetcher = new SafePageFetcher(PUBLIC, uri -> {
            requests.incrementAndGet();
            return new SafePageFetcher.Response(302, "/loop", "text/html", "");
        });
        var page = fetcher.fetch("https://jobs.example.com/role");
        assertThat(page.linkStatus()).isEqualTo(SafePageFetcher.STATUS_UNKNOWN);
        assertThat(requests).hasValue(SafePageFetcher.MAX_REDIRECTS + 1);
    }

    @Test void closedRestrictedAndCaptchaPagesAreReportedWithoutBeingTreatedAsRejection() {
        var closed = new SafePageFetcher(PUBLIC,
                uri -> new SafePageFetcher.Response(404, null, "text/html", "")).fetch("https://jobs.example.com/1");
        assertThat(closed.linkStatus()).isEqualTo(SafePageFetcher.STATUS_CLOSED);

        var forbidden = new SafePageFetcher(PUBLIC,
                uri -> new SafePageFetcher.Response(403, null, "text/html", "")).fetch("https://jobs.example.com/2");
        assertThat(forbidden.linkStatus()).isEqualTo(SafePageFetcher.STATUS_RESTRICTED);

        var captcha = new SafePageFetcher(PUBLIC,
                uri -> new SafePageFetcher.Response(200, null, "text/html", "<p>请完成验证码后继续</p>"))
                .fetch("https://jobs.example.com/3");
        assertThat(captcha.linkStatus()).isEqualTo(SafePageFetcher.STATUS_RESTRICTED);

        var failure = new SafePageFetcher(PUBLIC, uri -> {
            throw new IOException("timeout");
        }).fetch("https://jobs.example.com/4");
        assertThat(failure.linkStatus()).isEqualTo(SafePageFetcher.STATUS_UNKNOWN);
        assertThat(failure.note()).contains("网络");
    }
}

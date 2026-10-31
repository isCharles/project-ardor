package com.projectardor.jobsearch.discovery.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.projectardor.common.security.ExternalBaseUrlPolicy;

/**
 * Fetches a public job page without following redirects blindly. Every hop is
 * re-validated by the same public-address policy used for configured base URLs,
 * so a redirect cannot reach localhost, private networks or cloud metadata.
 */
@Component
public class SafePageFetcher {

    static final int MAX_REDIRECTS = 5;
    static final int MAX_BODY_BYTES = 512 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);
    private static final List<String> RESTRICTION_MARKERS = List.of(
            "captcha", "验证码", "人机验证", "安全验证", "登录后查看", "请登录后",
            "sign in to view", "access denied", "unusual traffic");

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_RESTRICTED = "RESTRICTED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    public interface UrlGuard {
        String validate(String url);
    }

    record Response(int status, String location, String contentType, String body) {
    }

    @FunctionalInterface
    interface Transport {
        Response get(URI uri) throws IOException, InterruptedException;
    }

    public record Page(String finalUrl, int status, String body, String linkStatus, String note) {
    }

    private final UrlGuard guard;
    private final Transport transport;

    @Autowired
    public SafePageFetcher(ExternalBaseUrlPolicy policy) {
        this(policy::validatePublicUrl, jdkTransport());
    }

    SafePageFetcher(UrlGuard guard, Transport transport) {
        this.guard = guard;
        this.transport = transport;
    }

    public Page fetch(String rawUrl) {
        String current = guard.validate(rawUrl);
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            URI uri;
            try {
                uri = URI.create(current);
            } catch (IllegalArgumentException malformed) {
                return new Page(current, 0, "", STATUS_UNKNOWN, "链接格式无效");
            }
            Response response;
            try {
                response = transport.get(uri);
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Page(current, 0, "", STATUS_UNKNOWN, "网络请求失败或超时");
            }
            if (response.status() >= 300 && response.status() < 400) {
                if (response.location() == null || response.location().isBlank()) {
                    return new Page(current, response.status(), "", STATUS_UNKNOWN, "重定向缺少目标地址");
                }
                try {
                    current = guard.validate(uri.resolve(response.location()).toString());
                } catch (IllegalArgumentException blocked) {
                    throw new IllegalArgumentException("岗位链接重定向到了不允许访问的地址", blocked);
                }
                continue;
            }
            return finish(current, response);
        }
        return new Page(current, 0, "", STATUS_UNKNOWN, "重定向次数过多");
    }

    private Page finish(String url, Response response) {
        String body = response.body() == null ? "" : response.body();
        String linkStatus;
        String note = null;
        if (response.status() >= 200 && response.status() < 300) {
            if (restricted(body)) {
                linkStatus = STATUS_RESTRICTED;
                note = "页面要求登录或验证码";
            } else {
                linkStatus = STATUS_OPEN;
            }
        } else if (response.status() == 401 || response.status() == 403
                || response.status() == 407 || response.status() == 429) {
            linkStatus = STATUS_RESTRICTED;
            note = "来源限制了自动访问";
        } else if (response.status() == 404 || response.status() == 410) {
            linkStatus = STATUS_CLOSED;
            note = "页面返回已关闭状态";
        } else {
            linkStatus = STATUS_UNKNOWN;
            note = "未能确认页面状态";
        }
        return new Page(url, response.status(), body, linkStatus, note);
    }

    static boolean restricted(String body) {
        if (body == null || body.isBlank()) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return RESTRICTION_MARKERS.stream().anyMatch(lower::contains);
    }

    private static Transport jdkTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(READ_TIMEOUT)
                    .header("User-Agent", "ArdorJobDiscovery/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            // Receiving headers does not bound the body. Wait for the capped body
            // itself and cancel the exchange when the deadline expires, otherwise a
            // stalled page can hold an AI worker indefinitely.
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future =
                    client.sendAsync(request, info -> cappedBody(MAX_BODY_BYTES));
            HttpResponse<byte[]> response;
            try {
                response = future.get(READ_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                future.cancel(true);
                throw new IOException("读取页面超时", timeout);
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof IOException io) throw io;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IOException("页面请求失败", failure.getCause());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            boolean html = contentType.isEmpty() || contentType.toLowerCase(Locale.ROOT).contains("html");
            String body = "";
            if (html) {
                byte[] bytes = response.body();
                int length = Math.min(bytes.length, MAX_BODY_BYTES);
                body = new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            String location = response.headers().firstValue("Location").orElse(null);
            return new Response(response.statusCode(), location, contentType, body);
        };
    }

    /** Collects at most {@code limit} bytes and stops the subscription once full. */
    private static HttpResponse.BodySubscriber<byte[]> cappedBody(int limit) {
        return new CappedBodySubscriber(limit);
    }

    private static final class CappedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result =
                new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;

        private CappedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (java.nio.ByteBuffer buffer : buffers) {
                int remaining = limit - collected.size();
                if (remaining <= 0) break;
                byte[] bytes = new byte[Math.min(remaining, buffer.remaining())];
                buffer.get(bytes);
                collected.write(bytes, 0, bytes.length);
            }
            if (collected.size() >= limit) {
                subscription.cancel();
                result.complete(collected.toByteArray());
            }
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(collected.toByteArray());
        }
    }
}

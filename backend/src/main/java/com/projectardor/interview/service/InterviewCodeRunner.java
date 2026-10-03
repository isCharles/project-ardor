package com.projectardor.interview.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/** Calls a separately operated sandbox. Never starts user code in the Ardor JVM. */
@Service
public class InterviewCodeRunner {
    private final RestClient client;
    private final boolean configured;
    private final Semaphore concurrentRuns = new Semaphore(2);

    public InterviewCodeRunner(@Value("${app.coding.runner-url:}") String runnerUrl) {
        this(runnerUrl, productionBuilder());
    }

    private static RestClient.Builder productionBuilder() {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(16));
        return RestClient.builder().requestFactory(requestFactory);
    }

    InterviewCodeRunner(String runnerUrl, RestClient.Builder builder) {
        String trimmed = runnerUrl.strip();
        configured = !trimmed.isEmpty();
        if (configured) {
            URI uri = URI.create(trimmed);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("代码运行器地址必须是 HTTP(S) 服务地址");
            }
            client = builder.baseUrl(trimmed.replaceAll("/+$", "")).build();
        } else {
            client = null;
        }
    }

    public boolean available() {
        return configured;
    }

    public CodeRunResult runJava(String code, String stdin) {
        if (!configured) {
            throw new IllegalStateException("代码运行器尚未配置");
        }
        if (code == null || code.isBlank() || code.length() > 20_000) {
            throw new IllegalArgumentException("Java 代码不能为空且不能超过 20000 字");
        }
        if (stdin != null && stdin.length() > 8_000) {
            throw new IllegalArgumentException("输入不能超过 8000 字");
        }
        if (!concurrentRuns.tryAcquire()) {
            throw new IllegalStateException("当前运行任务较多，请稍后重试");
        }
        try {
            JsonNode result = client.post().uri("/api/v2/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "language", "java",
                            "version", "*",
                            "files", List.of(Map.of("name", "Main.java", "content", code)),
                            "stdin", stdin == null ? "" : stdin,
                            "compile_timeout", 10_000,
                            "run_timeout", 3_000,
                            "run_cpu_time", 3_000,
                            "compile_memory_limit", 536_870_912,
                            "run_memory_limit", 268_435_456))
                    .retrieve().body(JsonNode.class);
            if (result == null) throw new IllegalStateException("代码运行器未返回结果");
            JsonNode compile = result.path("compile");
            JsonNode run = result.path("run");
            return new CodeRunResult(
                    truncate(compile.path("stdout").asText("")),
                    truncate(compile.path("stderr").asText("")),
                    truncate(run.path("stdout").asText("")),
                    truncate(run.path("stderr").asText("")),
                    run.isMissingNode() || run.isNull() ? compile.path("code").asInt(-1) : run.path("code").asInt(-1),
                    run.isMissingNode() || run.isNull() ? compile.path("status").asText("") : run.path("status").asText(""));
        } catch (RestClientException exception) {
            throw new IllegalStateException("代码运行器暂时不可用，请稍后重试", exception);
        } finally {
            concurrentRuns.release();
        }
    }

    private String truncate(String value) {
        return value.length() > 8_000 ? value.substring(0, 8_000) + "\n…输出已截断" : value;
    }

    public record CodeRunResult(String compileStdout, String compileStderr,
            String stdout, String stderr, int exitCode, String status) {
    }
}

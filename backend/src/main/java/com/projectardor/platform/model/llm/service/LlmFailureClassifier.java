package com.projectardor.platform.model.llm.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import com.projectardor.common.security.ExternalHostResolutionException;

/** Safe provider-independent descriptions. Never copy upstream response bodies into user errors. */
public final class LlmFailureClassifier {
    private LlmFailureClassifier() {}

    public static LlmCallException http(int status, Throwable cause) {
        return switch (status) {
            case 400 -> failure("LLM_BAD_REQUEST", "LLM 拒绝请求，请检查模型名称和协议", false, cause);
            case 401, 403 -> failure("LLM_AUTH_FAILED", "LLM 鉴权失败，请检查 API Key", false, cause);
            case 404 -> failure("LLM_NOT_FOUND", "LLM 接口或模型不存在，请检查 Base URL、协议和模型名称", false, cause);
            case 408, 429 -> failure("LLM_BUSY", "LLM 服务繁忙或触发限流，请稍后重试", true, cause);
            default -> failure("LLM_HTTP_ERROR", "LLM 请求失败（HTTP " + status + "）", status >= 500, cause);
        };
    }

    /** Null means no transport evidence; do not guess from exception text. */
    public static LlmCallException transport(Throwable cause) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        LlmCallException network = null;
        for (Throwable current = cause; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof UnknownHostException || current instanceof ExternalHostResolutionException)
                return failure("LLM_DNS_FAILED", "无法解析 LLM 域名，请检查 Docker DNS 或代理设置", true, cause);
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException || current instanceof TimeoutException)
                return failure("LLM_TIMEOUT", "连接 LLM 超时，请检查网络、代理或服务状态", true, cause);
            if (current instanceof SSLException)
                return failure("LLM_TLS_FAILED", "LLM TLS 连接失败，请检查代理与证书环境", true, cause);
            if (current instanceof ConnectException)
                network = failure("LLM_CONNECT_FAILED", "无法连接 LLM 服务，请检查 Base URL、网络或代理", true, cause);
            else if (current instanceof IOException && network == null)
                network = failure("LLM_NETWORK_FAILED", "LLM 网络请求失败，请检查网络或代理设置", true, cause);
        }
        return network;
    }

    private static LlmCallException failure(String code, String message, boolean retryable, Throwable cause) {
        return new LlmCallException(code, message, retryable, cause);
    }
}

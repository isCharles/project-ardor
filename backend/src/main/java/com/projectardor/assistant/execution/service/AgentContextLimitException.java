package com.projectardor.assistant.execution.service;

/**
 * Local, non-retryable input limit; never pretend an upstream network failure occurred.
 *
 * <p>{@code code} separates what the run could not do from what nobody could do: an irreducible single
 * input is not the same dead end as a recovery ladder that ran out of steps, and the transcript should
 * say which one happened.</p>
 */
public final class AgentContextLimitException extends IllegalStateException {
    private final String code;

    public AgentContextLimitException() {
        this("CONTEXT_LIMIT", "未说明具体原因");
    }

    public AgentContextLimitException(String code, String detail) {
        super("上下文超出本次模型的安全输入预算，请减少引用资料或拆分请求；原始记录仍保留，未发送超限请求。"
                + "（" + detail + "）");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

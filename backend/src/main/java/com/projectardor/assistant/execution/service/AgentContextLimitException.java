package com.projectardor.assistant.execution.service;

/** Local, non-retryable input limit; never pretend an upstream network failure occurred. */
public final class AgentContextLimitException extends IllegalStateException {
    public AgentContextLimitException() {
        super("上下文超出本次模型的安全输入预算，请减少引用资料或拆分请求；原始记录仍保留，未发送超限请求。");
    }
}

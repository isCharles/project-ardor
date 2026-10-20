package com.projectardor.platform.model.llm.service;

import java.util.UUID;

/**
 * Verifies that a configured chat model can execute a LangChain4j tool call.
 *
 * <p>The model/platform layer owns connection diagnostics, but the concrete
 * chat-model factory and the tool schema live in the assistant domain. The
 * assistant provides this probe so the platform layer never imports business
 * packages directly.</p>
 */
public interface ToolCallingProbe {

    void verify(LlmConfigService.LlmRuntimeConfig config, UUID userId);
}

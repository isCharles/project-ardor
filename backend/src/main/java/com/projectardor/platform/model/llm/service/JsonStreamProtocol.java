package com.projectardor.platform.model.llm.service;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Transport that reports model output while it is still being produced.
 * The gateway owns capacity and failure classification; a protocol owns the wire format.
 */
public interface JsonStreamProtocol extends ModelProtocol {

    /** Distinct from a provider failure so a cancelled turn is not reported as an outage. */
    String CANCELLED_CODE = "LLM_STREAM_CANCELLED";

    LlmGateway.LlmResult stream(
            LlmConfigService.LlmRuntimeConfig config,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onDelta,
            BooleanSupplier stopRequested) throws Exception;
}

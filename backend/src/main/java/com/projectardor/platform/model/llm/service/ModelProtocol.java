package com.projectardor.platform.model.llm.service;

import com.projectardor.platform.model.llm.domain.LlmProvider;

/** Stable wire protocol identity, not a model brand or business capability. */
public interface ModelProtocol {
    LlmProvider provider();
}

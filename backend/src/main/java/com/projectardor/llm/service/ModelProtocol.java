package com.projectardor.llm.service;

import com.projectardor.llm.domain.LlmProvider;

/** Stable wire protocol identity, not a model brand or business capability. */
public interface ModelProtocol {
    LlmProvider provider();
}

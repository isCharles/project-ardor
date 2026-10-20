package com.projectardor.llm.service;

/** Protocol owns request/response wire details; the gateway owns failure classification. */
public interface JsonModelProtocol extends ModelProtocol {
    LlmGateway.LlmResult complete(LlmConfigService.LlmRuntimeConfig config,String systemPrompt,String userPrompt) throws Exception;
    static LlmGateway.LlmResult result(String content,String model) {
        if(content==null || content.isBlank())
            throw new LlmCallException("LLM_EMPTY_RESPONSE","LLM 返回中没有可用内容",false,null);
        return new LlmGateway.LlmResult(content,model);
    }
}

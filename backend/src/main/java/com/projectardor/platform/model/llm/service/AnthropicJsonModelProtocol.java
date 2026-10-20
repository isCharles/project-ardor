package com.projectardor.platform.model.llm.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import tools.jackson.databind.ObjectMapper;

@Component
public class AnthropicJsonModelProtocol implements JsonModelProtocol {
    private final ObjectMapper mapper;
    private final RestClient.Builder clients;
    public AnthropicJsonModelProtocol(ObjectMapper mapper,RestClient.Builder clients) {this.mapper=mapper;this.clients=clients;}
    @Override public LlmProvider provider(){return LlmProvider.ANTHROPIC_COMPATIBLE;}
    @Override public LlmGateway.LlmResult complete(LlmConfigService.LlmRuntimeConfig config,String systemPrompt,String userPrompt) throws Exception {
        var client=clients.clone().defaultHeader("x-api-key",config.apiKey()).defaultHeader("anthropic-version","2023-06-01").build();
        var body=Map.of("model",config.model(),"max_tokens",4096,"temperature",0.2,"system",systemPrompt,
                "messages",List.of(Map.of("role","user","content",userPrompt)));
        var root=mapper.readTree(client.post().uri(config.baseUrl()+"/v1/messages")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        if("max_tokens".equals(root.path("stop_reason").asText()))
            throw new LlmCallException("LLM_OUTPUT_TRUNCATED","模型输出达到长度上限，未保存不完整结果",false,null);
        return JsonModelProtocol.result(root.path("content").path(0).path("text").asText(),root.path("model").asText(config.model()));
    }
}

package com.projectardor.platform.model.llm.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiJsonModelProtocol implements JsonModelProtocol {
    private final ObjectMapper mapper;
    private final RestClient.Builder clients;
    public OpenAiJsonModelProtocol(ObjectMapper mapper,RestClient.Builder clients) {this.mapper=mapper;this.clients=clients;}
    @Override public LlmProvider provider(){return LlmProvider.OPENAI_COMPATIBLE;}
    @Override public LlmGateway.LlmResult complete(LlmConfigService.LlmRuntimeConfig config,String systemPrompt,String userPrompt) throws Exception {
        var client=clients.clone().defaultHeader("Authorization","Bearer "+config.apiKey()).build();
        var body=Map.of("model",config.model(),"temperature",0.2,"messages",List.of(
                Map.of("role","system","content",systemPrompt),Map.of("role","user","content",userPrompt)));
        var root=mapper.readTree(client.post().uri(config.baseUrl()+"/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        if("length".equals(root.path("choices").path(0).path("finish_reason").asText()))
            throw new LlmCallException("LLM_OUTPUT_TRUNCATED","模型输出达到长度上限，未保存不完整结果",false,null);
        return JsonModelProtocol.result(root.path("choices").path(0).path("message").path("content").asText(),
                root.path("model").asText(config.model()));
    }
}

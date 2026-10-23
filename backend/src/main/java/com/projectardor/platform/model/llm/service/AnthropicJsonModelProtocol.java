package com.projectardor.platform.model.llm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AnthropicJsonModelProtocol implements JsonModelProtocol {
    private static final Logger log = LoggerFactory.getLogger(AnthropicJsonModelProtocol.class);
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
        String content = text(root.path("content"));
        if (content.isBlank()) {
            // Do not log content. Block types are enough to diagnose a provider/protocol mismatch.
            log.warn("Anthropic JSON response has no text block: model={}, stopReason={}, blockTypes={}",
                    root.path("model").asText(config.model()), root.path("stop_reason").asText(""),
                    blockTypes(root.path("content")));
        }
        return JsonModelProtocol.result(content,root.path("model").asText(config.model()));
    }

    private static String text(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.path("text").asText("");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText("")) || block.has("text")) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.path("text").asText(""));
            }
        }
        return text.toString();
    }

    private static List<String> blockTypes(JsonNode content) {
        if (content == null || !content.isArray()) return List.of();
        List<String> types = new ArrayList<>();
        for (JsonNode block : content) {
            types.add(block.path("type").asText("unknown"));
            if (types.size() >= 8) break;
        }
        return types;
    }
}

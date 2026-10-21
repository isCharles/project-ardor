package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import tools.jackson.databind.json.JsonMapper;

class AgentModelProtocolTests {
    private final Duration connect=Duration.ofSeconds(2),read=Duration.ofSeconds(5);
    @Test void actualSdkHttpFailuresUseSharedSafeClassificationForBothProtocols() throws Exception {
        for (var provider : LlmProvider.values()) for (int status : new int[] {400, 401, 403, 404, 429, 503}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "{\"error\":{\"message\":\"secret-provider-body\",\"type\":\"fixture_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var config = new LlmConfigService.LlmRuntimeConfig(provider, "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-model", "fake-only");
                var factory = new LangChainModelFactory(mock(LlmConfigService.class), "", 0, connect, read,
                        List.of(new OpenAiAgentModelProtocol(), new AnthropicAgentModelProtocol()));
                Throwable failure = catchThrowable(() -> factory.create(config).chat(UserMessage.from("synthetic")));
                assertThat(failure).isNotNull();
                var classified = (com.projectardor.platform.model.llm.service.LlmCallException) CareerAgentService.classifyStreamFailure(failure);
                String code = switch (status) {
                    case 400 -> "LLM_BAD_REQUEST";
                    case 401, 403 -> "LLM_AUTH_FAILED";
                    case 404 -> "LLM_NOT_FOUND";
                    case 429 -> "LLM_BUSY";
                    default -> "LLM_HTTP_ERROR";
                };
                assertThat(classified.getCode()).as("%s HTTP %s", provider, status).isEqualTo(code);
                assertThat(classified.getMessage()).doesNotContain("secret-provider-body", "fake-only");
            } finally { server.stop(0); }
        }
    }
    @Test void registeredAdapterOwnsReasoningAndReceivesTimeoutTransportAndZeroHiddenRetries() {
        var configs=mock(LlmConfigService.class);var protocol=mock(AgentModelProtocol.class);var other=mock(AgentModelProtocol.class);
        when(protocol.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);when(other.provider()).thenReturn(LlmProvider.ANTHROPIC_COMPATIBLE);
        var config=new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE,"https://llm.example","fixture-model","fake-only");
        var profile=new ModelReasoningPolicy.Profile("adapter-owned",Map.of(),null,null,4096,false,false);
        var chat=mock(ChatModel.class);
        when(protocol.reasoning(config,ReasoningDepth.DEEP)).thenReturn(profile);
        when(protocol.chat(eq(config),eq(profile),any(),eq(read),eq(0))).thenReturn(chat);
        var factory=new LangChainModelFactory(configs,"",0,connect,read,List.of(protocol,other));
        assertThat(factory.create(config,ReasoningDepth.DEEP)).isSameAs(chat);
        var transport=org.mockito.ArgumentCaptor.forClass(HttpClientBuilder.class);
        verify(protocol).chat(eq(config),eq(profile),transport.capture(),eq(read),eq(0));
        assertThat(transport.getValue().connectTimeout()).isEqualTo(connect);
        assertThat(transport.getValue().readTimeout()).isEqualTo(read);
        verify(other,never()).chat(any(),any(),any(),any(),anyInt());
    }
    @Test void streamingStillUsesTheCurrentOwnerConfigAndCancellationLease() {
        UUID owner=UUID.randomUUID();var configs=mock(LlmConfigService.class);
        var config=new LlmConfigService.LlmRuntimeConfig(LlmProvider.OPENAI_COMPATIBLE,"https://llm.example","fixture-model","fake-only");
        when(configs.getRuntimeConfig(owner)).thenReturn(config);
        var protocol=mock(AgentModelProtocol.class);var other=mock(AgentModelProtocol.class);
        when(protocol.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);when(other.provider()).thenReturn(LlmProvider.ANTHROPIC_COMPATIBLE);
        var profile=new ModelReasoningPolicy.Profile(null,Map.of(),null,null,4096,false,false);
        when(protocol.reasoning(config,ReasoningDepth.AUTO)).thenReturn(profile);
        var stream=mock(StreamingChatModel.class);
        when(protocol.streaming(eq(config),eq(profile),any(),eq(read))).thenReturn(stream);
        var factory=new LangChainModelFactory(configs,"",0,connect,read,List.of(protocol,other));
        assertThat(factory.createStreaming(owner,ReasoningDepth.AUTO,new AgentStreamLease())).isSameAs(stream);
        var transport=org.mockito.ArgumentCaptor.forClass(HttpClientBuilder.class);
        verify(protocol).streaming(eq(config),eq(profile),transport.capture(),eq(read));
        assertThat(transport.getValue()).isInstanceOf(LeasedHttpClientBuilder.class);
        assertThat(factory.reasoningProfile(owner)).isSameAs(profile);
        verify(other,never()).streaming(any(),any(),any(),any());
    }
    @Test void bothRealSdkAdaptersMakeRequestsAndParseRepliesAgainstLocalHttp() throws Exception {
        for(var provider:LlmProvider.values()) for(String prefix:List.of("","/gateway")) {
            var request=new AtomicReference<Captured>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                request.set(new Captured(exchange.getRequestURI().getPath(),exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("x-api-key"),body));
                String json=provider==LlmProvider.OPENAI_COMPATIBLE
                        ? "{\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture-ok\"},\"finish_reason\":\"stop\"}]}"
                        : "{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":[{\"type\":\"text\",\"text\":\"fixture-ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
                byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
            try {
                String base="http://127.0.0.1:"+server.getAddress().getPort()+prefix;
                var config=new LlmConfigService.LlmRuntimeConfig(provider,base,"fixture-model","fake-only");
                var factory=new LangChainModelFactory(mock(LlmConfigService.class),"",0,connect,read,
                        List.of(new OpenAiAgentModelProtocol(),new AnthropicAgentModelProtocol()));
                String output=factory.create(config).chat(UserMessage.from("synthetic")).aiMessage().text();
                assertThat(output).isEqualTo("fixture-ok");var sent=request.get();
                assertThat(sent.path()).isEqualTo(prefix+(provider==LlmProvider.OPENAI_COMPATIBLE?"/chat/completions":"/v1/messages"));
                assertThat(provider==LlmProvider.OPENAI_COMPATIBLE?sent.authorization():sent.apiKey()).isEqualTo(provider==LlmProvider.OPENAI_COMPATIBLE?"Bearer fake-only":"fake-only");
                assertThat(JsonMapper.builder().build().readTree(sent.body()).path("model").asText()).isEqualTo("fixture-model");
            } finally {server.stop(0);}
        }
    }
    @Test void factoriesContainNoProviderWireBranchesOrSdkSpecificBuilders() throws Exception {
        String factory=Files.readString(Path.of("src/main/java/com/projectardor/assistant/execution/service/LangChainModelFactory.java"),StandardCharsets.UTF_8);
        assertThat(factory).doesNotContain("case OPENAI_COMPATIBLE","case ANTHROPIC_COMPATIBLE","OpenAiChatModel.builder","AnthropicChatModel.builder");
        String gateway=Files.readString(Path.of("src/main/java/com/projectardor/platform/model/llm/service/CompatibleLlmGateway.java"),StandardCharsets.UTF_8);
        assertThat(gateway).doesNotContain("case OPENAI_COMPATIBLE","case ANTHROPIC_COMPATIBLE","/chat/completions","/v1/messages");
    }
    @Test void bothSdkProtocolsCompleteAnActualToolRoundTrip() throws Exception {
        for(var provider:LlmProvider.values()) {
            var exchanges=new java.util.concurrent.atomic.AtomicInteger();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                exchange.getRequestBody().readAllBytes();
                boolean first=exchanges.incrementAndGet()==1;
                String json;
                if(provider==LlmProvider.OPENAI_COMPATIBLE) json=first
                        ? "{\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-fixture\",\"type\":\"function\",\"function\":{\"name\":\"protocol_probe\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"
                        : "{\"id\":\"fixture\",\"model\":\"fixture-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture-ok\"},\"finish_reason\":\"stop\"}]}";
                else json=first
                        ? "{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-fixture\",\"name\":\"protocol_probe\",\"input\":{}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
                        : "{\"id\":\"fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fixture-model\",\"content\":[{\"type\":\"text\",\"text\":\"fixture-ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
                byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
            try {
                var config=new LlmConfigService.LlmRuntimeConfig(provider,"http://127.0.0.1:"+server.getAddress().getPort(),"fixture-model","fake-only");
                var factory=new LangChainModelFactory(mock(LlmConfigService.class),"",0,connect,read,List.of(new OpenAiAgentModelProtocol(),new AnthropicAgentModelProtocol()));
                var tool=new ProtocolProbe();
                var assistant=dev.langchain4j.service.AiServices.builder(ProbeAssistant.class).chatModel(factory.create(config))
                        .tools(tool).maxToolCallingRoundTrips(2).build();
                assertThat(assistant.chat("synthetic")).isEqualTo("fixture-ok");
                assertThat(tool.calls.get()).isEqualTo(1);assertThat(exchanges.get()).isEqualTo(2);
            } finally {server.stop(0);}
        }
    }
    private interface ProbeAssistant {String chat(String message);}
    public static final class ProtocolProbe {
        private final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        @dev.langchain4j.agent.tool.Tool(name="protocol_probe",value="Synthetic transport test")
        public String probe(){calls.incrementAndGet();return "fixture-tool-ok";}
    }
    @Test void bothRealStreamingSdkAdaptersReadTextEventsThroughTheCancellationTransport() throws Exception {
        for(var provider:LlmProvider.values()) {
            var request=new AtomicReference<Captured>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                request.set(new Captured(exchange.getRequestURI().getPath(),exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("x-api-key"),new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                byte[] bytes=events(provider).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
            try {
                UUID owner=UUID.randomUUID();var configs=mock(LlmConfigService.class);
                when(configs.getRuntimeConfig(owner)).thenReturn(new LlmConfigService.LlmRuntimeConfig(provider,
                        "http://127.0.0.1:"+server.getAddress().getPort(),"fixture-model","fake-only"));
                var factory=new LangChainModelFactory(configs,"",0,connect,read,List.of(new OpenAiAgentModelProtocol(),new AnthropicAgentModelProtocol()));
                var result=new java.util.concurrent.CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse>();
                var partial=new StringBuffer();var lease=new AgentStreamLease();
                factory.createStreaming(owner,ReasoningDepth.AUTO,lease).chat("synthetic",
                        new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String value){partial.append(value);}
                    @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response){result.complete(response);}
                    @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
                });
                assertThat(result.get(5,java.util.concurrent.TimeUnit.SECONDS).aiMessage().text()).isEqualTo("fixture-ok");
                assertThat(partial.toString()).isEqualTo("fixture-ok");
                assertThat(request.get().path()).isEqualTo(provider==LlmProvider.OPENAI_COMPATIBLE?"/chat/completions":"/v1/messages");
                assertThat(JsonMapper.builder().build().readTree(request.get().body()).path("stream").asBoolean()).isTrue();
                lease.stop(()->{});
            } finally {server.stop(0);}
        }
    }
    @Test void streamingSdkErrorsHaveTheSameSafeClassification() throws Exception {
        for (var provider : LlmProvider.values()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "{\"error\":{\"message\":\"secret-provider-body\",\"type\":\"fixture_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            var lease = new AgentStreamLease();
            try {
                UUID owner = UUID.randomUUID();
                var configs = mock(LlmConfigService.class);
                when(configs.getRuntimeConfig(owner)).thenReturn(new LlmConfigService.LlmRuntimeConfig(provider,
                        "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-model", "fake-only"));
                var factory = new LangChainModelFactory(configs, "", 0, connect, read, List.of(new OpenAiAgentModelProtocol(), new AnthropicAgentModelProtocol()));
                var error = new java.util.concurrent.CompletableFuture<Throwable>();
                factory.createStreaming(owner, ReasoningDepth.AUTO, lease).chat("synthetic",
                        new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String value) {}
                    @Override public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                        error.completeExceptionally(new AssertionError("Expected upstream rejection"));
                    }
                    @Override public void onError(Throwable failure) { error.complete(failure); }
                });
                var classified = (com.projectardor.platform.model.llm.service.LlmCallException)
                        CareerAgentService.classifyStreamFailure(error.get(8, java.util.concurrent.TimeUnit.SECONDS));
                assertThat(classified.getCode()).isEqualTo("LLM_AUTH_FAILED");
                assertThat(classified.isRetryable()).isFalse();
                assertThat(classified.getMessage()).doesNotContain("secret-provider-body", "fake-only");
            } finally { lease.stop(() -> {}); server.stop(0); }
        }
    }

    private String events(LlmProvider provider) {
        if(provider==LlmProvider.OPENAI_COMPATIBLE) return """
                data: {"id":"fixture","model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"fixture-ok"},"finish_reason":null}]}

                data: {"id":"fixture","model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        return """
                event: message_start
                data: {"type":"message_start","message":{"id":"fixture","type":"message","role":"assistant","model":"fixture-model","content":[],"usage":{"input_tokens":1,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"fixture-ok"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
    }
    private record Captured(String path,String authorization,String apiKey,String body) {}
}

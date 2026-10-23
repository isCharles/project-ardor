package com.projectardor.platform.model.llm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import tools.jackson.databind.ObjectMapper;

class ModelProtocolContractTests {
    @Test void registrationRejectsMissingDuplicateAndIdentitylessAdapters() {
        JsonModelProtocol open=mock(JsonModelProtocol.class);when(open.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        JsonModelProtocol anthropic=mock(JsonModelProtocol.class);when(anthropic.provider()).thenReturn(LlmProvider.ANTHROPIC_COMPATIBLE);
        assertThatThrownBy(()->new ModelProtocols<JsonModelProtocol>(List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
        assertThatThrownBy(()->new ModelProtocols<>(List.of(open,open,anthropic))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(()->new ModelProtocols<>(List.of(mock(JsonModelProtocol.class),anthropic))).isInstanceOf(IllegalStateException.class).hasMessageContaining("identity");
        assertThatThrownBy(()->new ModelProtocols<>(List.of(open,anthropic)).require(null))
                .isInstanceOfSatisfying(LlmCallException.class,e->assertThat(e.getCode()).isEqualTo("LLM_PROTOCOL_UNSUPPORTED"));
    }
    @Test void failureDoesNotGuessOrFallBackToAnotherProtocol() throws Exception {
        var open=mock(JsonModelProtocol.class);when(open.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
        var other=mock(JsonModelProtocol.class);when(other.provider()).thenReturn(LlmProvider.ANTHROPIC_COMPATIBLE);
        var gateway=new CompatibleLlmGateway(mock(LlmConfigService.class),List.of(open,other));
        var config=config(LlmProvider.OPENAI_COMPATIBLE);
        when(open.complete(config,"system","user")).thenThrow(new LlmCallException("LLM_AUTH_FAILED","Authentication failed",false,null));
        assertThatThrownBy(()->gateway.completeJson(config,"system","user"))
                .isInstanceOfSatisfying(LlmCallException.class,e->assertThat(e.getCode()).isEqualTo("LLM_AUTH_FAILED"));
        verify(other,never()).complete(any(),any(),any());
    }
    @Test void bothActualHttpProtocolsKeepFailureCodesAndRetryability() {
        for(var provider:LlmProvider.values()) for(var probe:List.of(
                new HttpProbe(400,"LLM_BAD_REQUEST",false),new HttpProbe(401,"LLM_AUTH_FAILED",false),
                new HttpProbe(403,"LLM_AUTH_FAILED",false),new HttpProbe(404,"LLM_NOT_FOUND",false),
                new HttpProbe(408,"LLM_BUSY",true),new HttpProbe(429,"LLM_BUSY",true),
                new HttpProbe(500,"LLM_HTTP_ERROR",true),new HttpProbe(502,"LLM_HTTP_ERROR",true))) {
            var fixture=fixture(provider);
            fixture.server.expect(requestTo(endpoint(provider))).andRespond(withStatus(HttpStatusCode.valueOf(probe.status()))
                    .body("private-provider-detail").contentType(MediaType.TEXT_PLAIN));
            assertThatThrownBy(()->fixture.gateway.completeJson(config(provider),"system","user"))
                    .isInstanceOfSatisfying(LlmCallException.class,e->{
                        assertThat(e.getCode()).isEqualTo(probe.code());assertThat(e.isRetryable()).isEqualTo(probe.retryable());
                        assertThat(e.getMessage()).doesNotContain("private-provider-detail","fake-only");
                    });
            fixture.server.verify();
        }
    }
    @Test void anthropicJsonReadsTextAfterReasoningBlocks() {
        var fixture=fixture(LlmProvider.ANTHROPIC_COMPATIBLE);
        fixture.server.expect(requestTo(endpoint(LlmProvider.ANTHROPIC_COMPATIBLE)))
                .andRespond(withSuccess("""
                        {"model":"fixture-model","stop_reason":"end_turn","content":[
                          {"type":"thinking","thinking":"private reasoning","signature":"sig"},
                          {"type":"text","text":"{\\"score\\":85}"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var result=fixture.gateway.completeJson(config(LlmProvider.ANTHROPIC_COMPATIBLE),"system","user");
        assertThat(result.content()).isEqualTo("{\"score\":85}");
        assertThat(result.model()).isEqualTo("fixture-model");
    }
    @Test void emptyAndInvalidPayloadsRemainNonRetryableAndDoNotLeakProviderBodies() {
        for(var provider:LlmProvider.values()) for(boolean empty:List.of(true,false)) {
            var fixture=fixture(provider);
            String body=empty ? (provider==LlmProvider.OPENAI_COMPATIBLE
                    ? "{\"choices\":[{\"message\":{\"content\":\"\"}}]}" : "{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}")
                    : "private-invalid-payload";
            fixture.server.expect(requestTo(endpoint(provider))).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->fixture.gateway.completeJson(config(provider),"system","user"))
                    .isInstanceOfSatisfying(LlmCallException.class,e->{
                        assertThat(e.getCode()).isEqualTo(empty ? "LLM_EMPTY_RESPONSE" : "LLM_INVALID_RESPONSE");
                        assertThat(e.isRetryable()).isFalse();assertThat(e.getMessage()).doesNotContain("private-invalid-payload");
                    });
            fixture.server.verify();
        }
    }
    @Test void networkFailureClassificationIsIndependentOfTheWireProtocol() throws Exception {
        for(var probe:List.of(new NetworkProbe(new UnknownHostException(),"LLM_DNS_FAILED"),
                new NetworkProbe(new SocketTimeoutException(),"LLM_TIMEOUT"),new NetworkProbe(new ConnectException(),"LLM_CONNECT_FAILED"),
                new NetworkProbe(new SSLException("fixture"),"LLM_TLS_FAILED"),new NetworkProbe(new java.io.IOException(),"LLM_NETWORK_FAILED"))) {
            var open=mock(JsonModelProtocol.class);when(open.provider()).thenReturn(LlmProvider.OPENAI_COMPATIBLE);
            var other=mock(JsonModelProtocol.class);when(other.provider()).thenReturn(LlmProvider.ANTHROPIC_COMPATIBLE);
            var config=config(LlmProvider.OPENAI_COMPATIBLE);
            when(open.complete(config,"s","u")).thenThrow(new ResourceAccessException("private",probe.cause()));
            var gateway=new CompatibleLlmGateway(mock(LlmConfigService.class),List.of(open,other));
            assertThatThrownBy(()->gateway.completeJson(config,"s","u")).isInstanceOfSatisfying(LlmCallException.class,e->{
                assertThat(e.getCode()).isEqualTo(probe.code());assertThat(e.isRetryable()).isTrue();
            });
        }
    }
    private Fixture fixture(LlmProvider provider) {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();var mapper=new ObjectMapper();
        return new Fixture(new CompatibleLlmGateway(mock(LlmConfigService.class),
                List.of(new OpenAiJsonModelProtocol(mapper,builder),new AnthropicJsonModelProtocol(mapper,builder))),server);
    }
    private LlmConfigService.LlmRuntimeConfig config(LlmProvider provider) {return new LlmConfigService.LlmRuntimeConfig(provider,"https://llm.example","fixture-model","fake-only");}
    private String endpoint(LlmProvider provider) {return "https://llm.example"+(provider==LlmProvider.OPENAI_COMPATIBLE?"/chat/completions":"/v1/messages");}
    private record Fixture(CompatibleLlmGateway gateway,MockRestServiceServer server) {}
    private record HttpProbe(int status,String code,boolean retryable) {}
    private record NetworkProbe(java.io.IOException cause,String code) {}
}

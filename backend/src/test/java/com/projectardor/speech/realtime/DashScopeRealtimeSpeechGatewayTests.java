package com.projectardor.speech.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DashScopeRealtimeSpeechGatewayTests {

    @Test
    void buildsGlobalRealtimeEndpointWithoutKeepingCompatibleModePath() {
        assertThat(DashScopeRealtimeSpeechGateway.realtimeUri(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3-asr-flash-realtime"))
                .hasToString("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime");
    }

    @Test
    void refusesWorkspaceEndpointThatUsesADifferentWebSocketProtocol() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                DashScopeRealtimeSpeechGateway.realtimeUri(
                        "https://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                        "qwen3-tts-flash-realtime"));
    }

    @Test
    void refusesARealtimeConnectionToAnUntrustedHost() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                DashScopeRealtimeSpeechGateway.realtimeUri(
                        "https://example.com/aliyuncs.com", "qwen3-asr-flash-realtime"));
    }

    @Test
    void mapsBatchSpeechModelsToTheirRealtimeCounterparts() {
        assertThat(DashScopeRealtimeSpeechGateway.realtimeModel(
                "qwen3-asr-flash", "fallback-asr", "asr"))
                .isEqualTo("qwen3-asr-flash-realtime");
        assertThat(DashScopeRealtimeSpeechGateway.realtimeModel(
                "qwen3-tts-flash", "fallback-tts", "tts"))
                .isEqualTo("qwen3-tts-flash-realtime");
        assertThat(DashScopeRealtimeSpeechGateway.realtimeModel(
                "qwen-audio-3.0-asr-flash-streaming", "fallback-asr", "asr"))
                .isEqualTo("fallback-asr");
    }
}

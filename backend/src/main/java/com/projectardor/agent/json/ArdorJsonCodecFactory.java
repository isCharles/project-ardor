package com.projectardor.agent.json;

import java.lang.reflect.Type;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.langchain4j.internal.Json;
import dev.langchain4j.spi.json.JsonCodecFactory;

public class ArdorJsonCodecFactory implements JsonCodecFactory {

    @Override
    public Json.JsonCodec create() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Json.JsonCodec() {
            @Override
            public String toJson(Object value) {
                try {
                    return mapper.writeValueAsString(value);
                } catch (JsonProcessingException exception) {
                    throw new IllegalArgumentException("无法序列化工具结果", exception);
                }
            }

            @Override
            public <T> T fromJson(String json, Class<T> type) {
                try {
                    return mapper.readValue(json, type);
                } catch (JsonProcessingException exception) {
                    throw new IllegalArgumentException("无法解析 JSON", exception);
                }
            }

            @Override
            public <T> T fromJson(String json, Type type) {
                try {
                    return mapper.readValue(json, mapper.getTypeFactory().constructType(type));
                } catch (JsonProcessingException exception) {
                    throw new IllegalArgumentException("无法解析 JSON", exception);
                }
            }
        };
    }
}

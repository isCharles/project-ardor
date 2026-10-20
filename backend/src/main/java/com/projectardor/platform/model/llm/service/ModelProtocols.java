package com.projectardor.platform.model.llm.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import com.projectardor.platform.model.llm.domain.LlmProvider;

/** Checked registration shared by JSON analysis and Agent transport adapters. */
public final class ModelProtocols<T extends ModelProtocol> {
    private final Map<LlmProvider,T> protocols;
    public ModelProtocols(List<T> implementations) {
        var registered=new EnumMap<LlmProvider,T>(LlmProvider.class);
        for(T protocol:implementations) {
            if(protocol==null || protocol.provider()==null)
                throw new IllegalStateException("Model protocol registration must have an identity");
            if(registered.putIfAbsent(protocol.provider(),protocol)!=null)
                throw new IllegalStateException("Duplicate model protocol: "+protocol.provider());
        }
        for(var required:LlmProvider.values()) if(!registered.containsKey(required))
            throw new IllegalStateException("Missing model protocol: "+required);
        protocols=Map.copyOf(registered);
    }
    public T require(LlmProvider provider) {
        T protocol=provider==null ? null : protocols.get(provider);
        if(protocol==null)throw new LlmCallException("LLM_PROTOCOL_UNSUPPORTED","当前服务不支持所选模型协议",false,null);
        return protocol;
    }
}

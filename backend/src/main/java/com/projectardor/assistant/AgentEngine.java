package com.projectardor.assistant;

import com.projectardor.assistant.conversation.AgentContext;
/**
 * Stable boundary between career orchestration and the selected LLM runtime.
 */
public interface AgentEngine {

    AgentResponse execute(AgentContext context);
}


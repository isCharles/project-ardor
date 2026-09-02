package com.projectardor.agent;

/**
 * Stable boundary between career orchestration and the selected LLM runtime.
 */
public interface AgentEngine {

    AgentResponse execute(AgentContext context);
}


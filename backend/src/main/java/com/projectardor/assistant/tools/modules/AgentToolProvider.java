package com.projectardor.assistant.tools.modules;

/** A local assistant adapter; not a plugin or a source of authorization. */
public interface AgentToolProvider {
    Object bind(AgentToolContext context);
}

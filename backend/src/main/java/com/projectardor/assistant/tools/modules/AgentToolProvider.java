package com.projectardor.assistant.tools.modules;

/** A local assistant adapter; not a plugin or a source of authorization. */
public interface AgentToolProvider {
    Object bind(AgentToolContext context);

    /** Model tool classes exposed without constructing an owner-bound adapter. */
    default java.util.List<Class<?>> toolTypes() { return java.util.List.of(); }
}

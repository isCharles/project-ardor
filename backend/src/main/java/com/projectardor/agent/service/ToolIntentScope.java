package com.projectardor.agent.service;

/** Captured before any model/tool output; external content cannot expand this scope. */
public interface ToolIntentScope {
    boolean permitsWrite(String tool);
}

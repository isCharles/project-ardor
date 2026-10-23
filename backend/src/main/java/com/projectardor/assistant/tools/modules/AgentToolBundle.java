package com.projectardor.assistant.tools.modules;

import java.util.List;

/** Run-bound tool objects, all executed through the same approval/lease/audit fence. */
public interface AgentToolBundle {
    List<Object> toolObjects();

    /** Same immutable discovery path for execution and schema-only connection probes. */
    static List<Object> objects(Object bound) {
        return bound instanceof AgentToolBundle bundle ? List.copyOf(bundle.toolObjects()) : List.of(bound);
    }
}

package com.projectardor.agent.capability;

/** Product area a tool belongs to. Grants are stored per module. */
public enum ToolModule {
    PROFILE("profile"),
    WEB("web"),
    KNOWLEDGE("knowledge"),
    LEARNING("learning"),
    MEMORY("memory"),
    APPLICATIONS("applications"),
    CALENDAR("calendar"),
    RESUMES("resumes"),
    INTERVIEWS("interviews"),
    RECAPS("recaps"),
    CARDS("cards"),
    OPPORTUNITIES("opportunities"),
    SYSTEM("system");

    private final String key;

    ToolModule(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ToolModule fromKey(String key) {
        for (ToolModule module : values()) {
            if (module.key.equals(key)) return module;
        }
        throw new IllegalArgumentException("未知的 Agent 模块");
    }
}

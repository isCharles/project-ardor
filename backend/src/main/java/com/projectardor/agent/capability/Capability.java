package com.projectardor.agent.capability;

/** What a tool does to data, ordered by the trust level it needs to run unattended. */
public enum Capability {
    READ(0),
    CREATE(1),
    UPDATE(2),
    EXECUTE(3),
    DELETE(4);

    private final int minimumLevel;

    Capability(int minimumLevel) {
        this.minimumLevel = minimumLevel;
    }

    public int minimumLevel() {
        return minimumLevel;
    }

    /** Deleting is never auto-approved; it always becomes a proposal card. */
    public boolean requiresProposal() {
        return this == DELETE;
    }
}

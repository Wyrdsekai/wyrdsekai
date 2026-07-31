package org.wyrdsekai.common.topology;

/**
 * Ownership classification for rooms in the household topology.
 *
 * <ul>
 *   <li>{@link #PERSONAL} — belongs to a single human (e.g. their workshop). Not replicated.</li>
 *   <li>{@link #SHARED} — accessible by multiple household members. Replicated across nodes.</li>
 *   <li>{@link #AGENT_HOME} — an agent's personal Home room (memory palace). Replicated to ensure survival.</li>
 * </ul>
 */
public enum RoomOwnership {

    /** Belongs to a single human. Not replicated — lives on that human's node. */
    PERSONAL,

    /** Shared across the household. Replicated to surviving nodes. */
    SHARED,

    /** An agent's Home room (memory palace). Replicated to ensure soul continuity. */
    AGENT_HOME;

    /**
     * Whether rooms with this ownership should be replicated across household nodes.
     * Personal rooms live only on their owner's node; shared and agent-home rooms
     * are replicated for availability and continuity.
     */
    public boolean replicates() {
        return this == SHARED || this == AGENT_HOME;
    }
}

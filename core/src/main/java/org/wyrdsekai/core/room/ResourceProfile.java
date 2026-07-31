package org.wyrdsekai.core.room;

/**
 * Resource profile for agent Home furnishing (§87.6).
 * Determines which objects appear, capacity limits, and monitoring levels.
 * Auto-detected from hardware or model size, human confirms.
 */
public enum ResourceProfile {

    /**
     * Phone, Pi Zero (2-4GB, no GPU).
     * Home only. Inference relayed via Between.
     */
    SEED("seed", 69, 10, 10, false, false, false, false),

    /**
     * Pi 4, old laptop (4-8GB, no GPU).
     * Home + basic rooms. FULL soul-vessel.
     */
    SPROUT("sprout", 461, 20, 50, false, false, false, false),

    /**
     * Desktop, laptop (8-16GB, small GPU).
     * Foundation + interface rooms. Periodic mirror checks.
     */
    SAPLING("sapling", 461, 30, 200, true, false, false, false),

    /**
     * Home server, NAS (16-64GB, GPU).
     * All rooms. DEEP soul-vessel with hybrid retrieval. Full Forge.
     */
    TREE("tree", 3927, 50, -1, true, true, true, true),

    /**
     * Multi-node cluster.
     * Everything + federation + per-agent expansions.
     */
    GROVE("grove", 3927, 50, -1, true, true, true, true);

    private final String id;
    private final int soulVesselTokens;
    private final int memoryChestCapacity;
    private final int mailboxCapacity; // -1 = unlimited
    private final boolean hasJournal;
    private final boolean hasThreadSpool;
    private final boolean hasDreamJournal;
    private final boolean hasWardStone;

    ResourceProfile(String id, int soulVesselTokens, int memoryChestCapacity,
                    int mailboxCapacity, boolean hasJournal, boolean hasThreadSpool,
                    boolean hasDreamJournal, boolean hasWardStone) {
        this.id = id;
        this.soulVesselTokens = soulVesselTokens;
        this.memoryChestCapacity = memoryChestCapacity;
        this.mailboxCapacity = mailboxCapacity;
        this.hasJournal = hasJournal;
        this.hasThreadSpool = hasThreadSpool;
        this.hasDreamJournal = hasDreamJournal;
        this.hasWardStone = hasWardStone;
    }

    public String id() { return id; }
    public int soulVesselTokens() { return soulVesselTokens; }
    public int memoryChestCapacity() { return memoryChestCapacity; }
    public int mailboxCapacity() { return mailboxCapacity; }
    public boolean hasJournal() { return hasJournal; }
    public boolean hasThreadSpool() { return hasThreadSpool; }
    public boolean hasDreamJournal() { return hasDreamJournal; }
    public boolean hasWardStone() { return hasWardStone; }

    /** Whether mirror checks run periodically (sapling+). */
    public boolean hasPeriodicMirror() { return ordinal() >= SAPLING.ordinal(); }

    /** Whether the Forge does full consolidation (tree+). */
    public boolean hasFullForge() { return ordinal() >= TREE.ordinal(); }

    /** Whether hybrid retrieval (MEDIUM + top-3 fragments) is available. */
    public boolean hasHybridRetrieval() { return ordinal() >= TREE.ordinal(); }

    /** Soul extraction depth label: MEDIUM, FULL, or DEEP. */
    public String soulDepth() {
        if (soulVesselTokens <= 100) return "MEDIUM";
        if (soulVesselTokens <= 500) return "FULL";
        return "DEEP";
    }

    /** Parse from string ID (case-insensitive). Defaults to SPROUT. */
    public static ResourceProfile fromId(String id) {
        if (id == null) return SPROUT;
        for (var p : values()) {
            if (p.id.equalsIgnoreCase(id)) return p;
        }
        return SPROUT;
    }
}

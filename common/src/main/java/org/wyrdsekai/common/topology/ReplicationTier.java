package org.wyrdsekai.common.topology;

import java.time.Duration;

/**
 * Replication strategy tiers for rooms across household nodes.
 * Each tier defines a default snapshot interval and whether it supports
 * real-time event streaming between nodes.
 *
 * <ul>
 *   <li>{@link #EVENT_SOURCED} — full event stream replicated in real time. Zero snapshot interval.</li>
 *   <li>{@link #WRITE_THROUGH} — writes immediately replicated, snapshot every 30s.</li>
 *   <li>{@link #PERIODIC} — snapshot every 60s, no event streaming.</li>
 *   <li>{@link #LAZY} — snapshot every 5 minutes, no event streaming.</li>
 *   <li>{@link #CONFIG_ONLY} — only configuration/metadata replicated. Snapshot every 10 minutes.</li>
 * </ul>
 */
public enum ReplicationTier {

    /** Full event stream replicated in real time. */
    EVENT_SOURCED(Duration.ZERO, true),

    /** Writes immediately replicated; periodic snapshots every 30 seconds. */
    WRITE_THROUGH(Duration.ofSeconds(30), true),

    /** Periodic snapshots every 60 seconds; no event streaming. */
    PERIODIC(Duration.ofSeconds(60), false),

    /** Lazy snapshots every 5 minutes; no event streaming. */
    LAZY(Duration.ofMinutes(5), false),

    /** Only configuration/metadata replicated; snapshots every 10 minutes. */
    CONFIG_ONLY(Duration.ofMinutes(10), false);

    private final Duration defaultSnapshotInterval;
    private final boolean supportsEventStreaming;

    ReplicationTier(Duration defaultSnapshotInterval, boolean supportsEventStreaming) {
        this.defaultSnapshotInterval = defaultSnapshotInterval;
        this.supportsEventStreaming = supportsEventStreaming;
    }

    /** Default interval between state snapshots for this tier. */
    public Duration defaultSnapshotInterval() {
        return defaultSnapshotInterval;
    }

    /** Whether this tier supports real-time event streaming between nodes. */
    public boolean supportsEventStreaming() {
        return supportsEventStreaming;
    }
}

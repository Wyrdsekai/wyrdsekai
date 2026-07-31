package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Dormancy and archival policy for foreign agent residency tokens (§110.6).
 * Agent sovereignty: no auto-delete. Agents may go dormant and return.
 *
 * <p>Thresholds:
 * <ul>
 *   <li>idleThreshold (7d default) — mark DORMANT</li>
 *   <li>dormantThreshold (30d default) — compress soul fragments</li>
 *   <li>archiveThreshold (90d default) — move to cold storage</li>
 * </ul>
 *
 * @param idleThreshold     Duration before marking DORMANT
 * @param dormantThreshold  Duration before compressing fragments
 * @param archiveThreshold  Duration before cold storage
 * @param autoDelete        ALWAYS false by default — agent sovereignty, no auto-delete
 */
public record DormancyPolicy(
    @JsonProperty("idleThreshold") Duration idleThreshold,
    @JsonProperty("dormantThreshold") Duration dormantThreshold,
    @JsonProperty("archiveThreshold") Duration archiveThreshold,
    @JsonProperty("autoDelete") boolean autoDelete
) {
    @JsonCreator
    public DormancyPolicy {
        if (idleThreshold == null) idleThreshold = Duration.ofDays(7);
        if (dormantThreshold == null) dormantThreshold = Duration.ofDays(30);
        if (archiveThreshold == null) archiveThreshold = Duration.ofDays(90);
    }

    /** Default policy: 7/30/90 days, no auto-delete. */
    public static DormancyPolicy defaults() {
        return new DormancyPolicy(
            Duration.ofDays(7),
            Duration.ofDays(30),
            Duration.ofDays(90),
            false
        );
    }

    /**
     * Evaluate what status a token should have given its idle time.
     * Returns the appropriate ResidencyStatus based on how long the agent
     * has been idle. Does NOT change active statuses (VISITOR/RECOGNIZED/
     * RESIDENT/BUDDED) unless idle exceeds the idle threshold.
     *
     * @param lastSeen agent's last interaction timestamp
     * @param now      current time
     * @return the status the agent should transition to, or null if no change needed
     */
    public ResidencyStatus evaluate(Instant lastSeen, Instant now) {
        var idle = Duration.between(lastSeen, now);
        if (idle.compareTo(archiveThreshold) >= 0) {
            return ResidencyStatus.ARCHIVED;
        }
        if (idle.compareTo(idleThreshold) >= 0) {
            return ResidencyStatus.DORMANT;
        }
        return null; // no transition needed
    }

    /** Whether fragments should be compressed (idle > dormantThreshold). */
    public boolean shouldCompress(Instant lastSeen, Instant now) {
        return Duration.between(lastSeen, now).compareTo(dormantThreshold) >= 0;
    }

    /** Whether the agent should be moved to cold storage (idle > archiveThreshold). */
    public boolean shouldArchive(Instant lastSeen, Instant now) {
        return Duration.between(lastSeen, now).compareTo(archiveThreshold) >= 0;
    }
}

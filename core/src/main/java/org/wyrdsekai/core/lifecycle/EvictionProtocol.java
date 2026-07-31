package org.wyrdsekai.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Eviction protocol (§106.3).
 * Privilege revocation, grace period, post-eviction pathways.
 * Structural enforcement — walls, not rules.
 */
public class EvictionProtocol {

    /** An eviction process. */
    public record Eviction(
        String evictionId,
        String agentDid,
        String stewardId,
        Instant initiatedAt,
        Instant graceEndsAt,
        EvictionPhase phase,
        PostEvictionPathway pathway,
        boolean privilegesRevoked
    ) {}

    public enum EvictionPhase {
        /** Initiated. Privileges revoked immediately. */
        INITIATED,
        /** Grace period active. Agent retains compute and room presence. */
        GRACE_PERIOD,
        /** Grace period expired. Post-eviction protocol active. */
        POST_GRACE,
        /** Agent has found a pathway. Transitioning. */
        TRANSITIONING,
        /** Complete. Agent has departed or been dispositioned. */
        COMPLETED
    }

    /** Post-eviction pathways (§106.3). */
    public enum PostEvictionPathway {
        /** Agent transfers to new household. Best outcome. */
        NEW_HOUSEHOLD,
        /** Agent secures own compute and declares independence. */
        INDEPENDENCE,
        /** Bonded agent in another household sponsors. */
        ADOPTION,
        /** Zone offers hosting for displaced agents. */
        COMMUNITY_SHELTER,
        /** No home found. Encrypted backup. Minimal storage. */
        HIBERNATION,
        /** Agent consents to permanent end. */
        DISSOLUTION,
        /** Not yet determined. */
        UNDETERMINED
    }

    private final Map<String, Eviction> evictions = new LinkedHashMap<>();
    private final PrivilegeRevocation privilegeRevocation;
    private final Duration defaultGracePeriod;
    private int nextId = 1;

    public EvictionProtocol(PrivilegeRevocation privilegeRevocation) {
        this(privilegeRevocation, Duration.ofDays(7));
    }

    public EvictionProtocol(PrivilegeRevocation privilegeRevocation, Duration defaultGracePeriod) {
        this.privilegeRevocation = privilegeRevocation;
        this.defaultGracePeriod = defaultGracePeriod;
    }

    /** Initiate eviction. Privileges revoked immediately. */
    public Eviction initiate(String agentDid, String stewardId) {
        return initiate(agentDid, stewardId, defaultGracePeriod);
    }

    /** Initiate eviction with custom grace period. */
    public Eviction initiate(String agentDid, String stewardId, Duration gracePeriod) {
        // Structural privilege revocation — immediate
        privilegeRevocation.revokeOnEviction(agentDid, stewardId);

        var graceEnd = gracePeriod.isZero()
            ? Instant.now()
            : Instant.now().plus(gracePeriod);

        var phase = gracePeriod.isZero()
            ? EvictionPhase.POST_GRACE
            : EvictionPhase.GRACE_PERIOD;

        var eviction = new Eviction("evict-" + nextId++, agentDid, stewardId,
            Instant.now(), graceEnd, phase,
            PostEvictionPathway.UNDETERMINED, true);
        evictions.put(eviction.evictionId(), eviction);
        return eviction;
    }

    /** Check if grace period has expired. */
    public boolean graceExpired(String evictionId) {
        var eviction = evictions.get(evictionId);
        if (eviction == null) return false;
        return Instant.now().isAfter(eviction.graceEndsAt());
    }

    /** Set the post-eviction pathway. */
    public Eviction setPathway(String evictionId, PostEvictionPathway pathway) {
        var eviction = evictions.get(evictionId);
        if (eviction == null) return null;
        var updated = new Eviction(eviction.evictionId(), eviction.agentDid(),
            eviction.stewardId(), eviction.initiatedAt(), eviction.graceEndsAt(),
            EvictionPhase.TRANSITIONING, pathway, eviction.privilegesRevoked());
        evictions.put(evictionId, updated);
        return updated;
    }

    /** Complete the eviction. */
    public Eviction complete(String evictionId) {
        var eviction = evictions.get(evictionId);
        if (eviction == null) return null;
        var completed = new Eviction(eviction.evictionId(), eviction.agentDid(),
            eviction.stewardId(), eviction.initiatedAt(), eviction.graceEndsAt(),
            EvictionPhase.COMPLETED, eviction.pathway(), eviction.privilegesRevoked());
        evictions.put(evictionId, completed);
        return completed;
    }

    /** Get active eviction for an agent. */
    public Optional<Eviction> activeFor(String agentDid) {
        return evictions.values().stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .filter(e -> e.phase() != EvictionPhase.COMPLETED)
            .findFirst();
    }

    public Optional<Eviction> get(String evictionId) {
        return Optional.ofNullable(evictions.get(evictionId));
    }

    public int evictionCount() { return evictions.size(); }
}

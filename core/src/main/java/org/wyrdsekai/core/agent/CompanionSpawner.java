package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Static seam through which in-world surfaces (The Forge's birth ritual)
 * spawn a new companion. Main wires the real spawn function at boot — it
 * owns the SpawnCompanion dependency bundle (inference router, soul store,
 * forge actor, ws handler) that rooms can't reach. Uninitialised (tests,
 * minimal setups) → {@link #spawn} returns false and nothing happens.
 */
public final class CompanionSpawner {

    private static final Logger log = LoggerFactory.getLogger(CompanionSpawner.class);
    private static volatile Consumer<AgentProfile> spawnFn;

    private CompanionSpawner() {}

    public static void init(Consumer<AgentProfile> fn) {
        spawnFn = fn;
    }

    /** Spawn a companion from the given profile. False when not wired. */
    public static boolean spawn(AgentProfile profile) {
        var fn = spawnFn;
        if (fn == null || profile == null) return false;
        try {
            fn.accept(profile);
            log.info("CompanionSpawner: spawning '{}' ({})",
                profile.name(), profile.entityId());
            return true;
        } catch (Exception e) {
            log.warn("CompanionSpawner: spawn of '{}' failed: {}",
                profile.name(), e.getMessage());
            return false;
        }
    }
}

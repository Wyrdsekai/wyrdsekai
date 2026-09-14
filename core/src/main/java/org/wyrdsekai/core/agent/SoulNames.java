package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A name is a label on a soul, not the soul's key.
 *
 * <p>{@code WYRDSEKAI_COMPANION_NAME} used to shape the entity id, the entity id resolved
 * the DID, and so a changed name was a new entity with no DID: a second soul was forged,
 * and the first one — respawned by her own name — kept living beside it. A household that
 * renamed its companion woke up with two, and was then asked whether to delete one.
 * (Field report, 2026-09-13.)</p>
 *
 * <p>This resolves an env name to the soul it means:</p>
 * <ol>
 *   <li>a soul born here whose name is this name — spawned under its own entity id,
 *       whatever that id derives from;</li>
 *   <li>a soul born here whose entity id this name derives to — the ordinary case;</li>
 *   <li>if no soul answers to the name and exactly one soul born here is unclaimed by any
 *       configured name, the name has changed: she is renamed forward, same soul, same DID,
 *       and a new manifest version records it;</li>
 *   <li>otherwise a birth, as before.</li>
 * </ol>
 */
public final class SoulNames {

    private static final Logger log = LoggerFactory.getLogger(SoulNames.class);

    private SoulNames() {}

    /** A soul born on this node: its manifest DID matches the record the birth wrote. */
    public static boolean locallyBorn(Path soulsDir, SoulManifest m) {
        if (m == null || m.did() == null || m.profile() == null || m.profile().entityId() == null) return false;
        var didFile = soulsDir.resolve(m.profile().entityId() + ".did");
        try {
            return Files.exists(didFile) && m.did().equals(Files.readString(didFile).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** Every non-archived agent soul born here. */
    public static List<SoulManifest> localAgentSouls(SoulStore store, Path soulsDir) {
        var out = new ArrayList<SoulManifest>();
        if (store == null) return out;
        try {
            for (var m : store.listLatest()) {
                var p = m.profile();
                if (p == null || !"agent".equals(p.entityType())) continue;
                if (locallyBorn(soulsDir, m)) out.add(m);
            }
        } catch (Exception e) {
            log.warn("Could not list persisted souls: {}", e.toString());
        }
        return out;
    }

    /**
     * The profile a configured name should spawn.
     *
     * @param envName        the configured name (null/blank → the default companion)
     * @param second         whether this is {@code WYRDSEKAI_COMPANION_NAME_2}
     * @param otherEnvNames  the other configured names, so a rename never claims a soul
     *                       another name already answers to
     * @param claimedEntityIds entity ids already resolved for other names (mutated: this one is added)
     */
    public static AgentProfile forEnvName(String envName, boolean second, List<String> otherEnvNames,
                                          SoulStore store, Path soulsDir, Set<String> claimedEntityIds) {
        var base = second ? Companions.additionalCompanion(envName) : Companions.defaultCompanion(envName);
        var locals = localAgentSouls(store, soulsDir);

        // 1. A soul that answers to this name, under whatever id it was born with.
        for (var m : locals) {
            var p = m.profile();
            if (claimedEntityIds.contains(p.entityId())) continue;
            if (p.name() != null && p.name().equalsIgnoreCase(base.name())) {
                claimedEntityIds.add(p.entityId());
                if (!p.entityId().equals(base.entityId())) {
                    log.info("Companion '{}' lives under entity id '{}' (renamed earlier) — spawning her, not a new '{}'",
                        p.name(), p.entityId(), base.entityId());
                }
                return Companions.forPersistedSoul(p.name(), p.entityId(), p.archetype() != null ? p.archetype() : base.archetype());
            }
        }
        // 2. The ordinary case: the id this name derives to is hers.
        for (var m : locals) {
            if (m.profile().entityId().equals(base.entityId()) && !claimedEntityIds.contains(base.entityId())) {
                claimedEntityIds.add(base.entityId());
                return base;
            }
        }
        // 3. Nobody answers to this name, and exactly one soul born here is spoken for by
        //    no configured name: the name changed. Rename her forward.
        var unclaimed = new ArrayList<SoulManifest>();
        for (var m : locals) {
            var p = m.profile();
            if (claimedEntityIds.contains(p.entityId())) continue;
            boolean namedElsewhere = false;
            for (var other : otherEnvNames) {
                if (other != null && p.name() != null && p.name().equalsIgnoreCase(other.trim())) namedElsewhere = true;
            }
            if (!namedElsewhere) unclaimed.add(m);
        }
        if (unclaimed.size() == 1 && envName != null && !envName.isBlank()) {
            var m = unclaimed.getFirst();
            var was = m.profile().name();
            var renamed = Companions.forPersistedSoul(base.name(), m.profile().entityId(),
                m.profile().archetype() != null ? m.profile().archetype() : base.archetype());
            try {
                store.store(m.withProfile(renamed).withManifestVersion(m.manifestVersion() + 1, Instant.now()));
                log.warn("WYRDSEKAI_COMPANION_NAME{} is '{}' but the only soul born here is '{}' ({}) — "
                    + "she is renamed forward: same soul, same DID {}. Nobody new was born. "
                    + "(`wyrd soul rename` does this deliberately.)",
                    second ? "_2" : "", base.name(), was, m.profile().entityId(), m.did());
            } catch (Exception e) {
                log.warn("Rename of '{}' to '{}' could not be recorded ({}); spawning her under the new name anyway",
                    was, base.name(), e.toString());
            }
            claimedEntityIds.add(m.profile().entityId());
            return renamed;
        }
        // 4. A birth.
        claimedEntityIds.add(base.entityId());
        return base;
    }

    public static String slug(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }
}

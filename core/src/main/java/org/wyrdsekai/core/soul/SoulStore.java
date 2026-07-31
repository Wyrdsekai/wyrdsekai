package org.wyrdsekai.core.soul;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent storage for soul manifests.
 * Soul manifests are versioned: each forge creates a new version.
 * Archived souls are soft-deleted (persist but marked inactive).
 *
 * Implementations: SqlSoulStore (libSQL/PostgreSQL via existing
 * persistence infrastructure).
 *
 * <p><b>F7b Phase 3d — canonical sub-record readers.</b> The four
 * sub-record accessors below ({@link #fragmentsFor}, {@link #bondsFor},
 * {@link #voiceProfileFor}, {@link #worldKnowledgeFor}) read directly
 * from canonical tables, bypassing the manifest blob. Prefer these
 * over the {@code SoulManifest.soulFragments()/bonds()/voiceProfile()/
 * worldKnowledge()} accessors in new code: the manifest fields are now
 * a transient hydration view (assembled by {@link SqlSoulStore#latest})
 * and may eventually be removed.</p>
 */
public interface SoulStore {

    /** Store a soul manifest (new version). */
    void store(SoulManifest manifest);

    /**
     * Persist one bond NOW, without re-storing the whole manifest.
     *
     * <p>2026-07-18 — bonds previously reached the canonical {@code bonds}
     * table only when a Forge cycle re-stored the manifest (see the
     * dual-write in {@code SqlSoulStore.store}). Every reader of that table
     * (bond crystal, Shelf, SSH bondsView) therefore saw nothing for a
     * companion that hadn't slept yet — with the day-scale energy economy
     * that gap is a whole day, not forty minutes. CompanionActor calls this
     * at bond-mutation time so the table tracks the live relationship.
     * Default no-op keeps in-memory/test stores unaffected.</p>
     */
    default void saveBond(Bond bond) {}

    /** Load a specific version of a soul manifest. */
    Optional<SoulManifest> load(String did, int version);

    /** Load the most recent version of a soul manifest. */
    Optional<SoulManifest> latest(String did);

    /** All versions for a DID, newest first. */
    List<SoulManifest> history(String did);

    /** Soft-delete: mark as archived but preserve data. */
    void archive(String did, String reason);

    /** Check if a soul exists (any version, including archived). */
    boolean exists(String did);

    /** Count total stored manifests (all versions, all DIDs). */
    int count();

    /**
     * List the latest (non-archived) manifest for every DID.
     * Returns one manifest per DID, newest version first.
     * Used by the /api/soul/list endpoint for soul provisioning.
     */
    default List<SoulManifest> listLatest() {
        // Default implementation returns empty — override in SQL stores.
        return List.of();
    }

    // ─── F7b Phase 3d: canonical-store readers ─────────────────────────────
    // These bypass the manifest blob and read directly from the canonical
    // sub-record tables. They are the preferred read path for new code.
    // The manifest blob's sub-record fields remain available for backward
    // compat (and continue to be hydrated via SqlSoulStore.latest), but
    // direct access through these methods is shorter and explicit about
    // the data source.

    /** Soul fragments for a DID (from world.db:soul_fragments, ordered). */
    default List<SoulFragment> fragmentsFor(String did) {
        return latest(did).map(m -> m.soulFragments() == null
            ? List.<SoulFragment>of() : m.soulFragments())
            .orElse(List.of());
    }

    /** Bonds for a DID (from world.db:bonds). */
    default List<Bond> bondsFor(String did) {
        return latest(did).map(m -> m.bonds() == null
            ? List.<Bond>of() : m.bonds())
            .orElse(List.of());
    }

    /** Voice profile for a DID (from world.db:voice_profiles). Empty if absent. */
    default Optional<VoiceProfile> voiceProfileFor(String did) {
        return latest(did).flatMap(m -> Optional.ofNullable(m.voiceProfile()));
    }

    /** World-knowledge map for a DID (from world.db:world_knowledge). */
    default Map<String, String> worldKnowledgeFor(String did) {
        return latest(did).map(m -> m.worldKnowledge() == null
            ? Map.<String, String>of() : m.worldKnowledge())
            .orElse(Map.of());
    }
}

package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Programmatic edit path for a companion's {@link VoiceProfile} (#409).
 *
 * <p>Shipped first so the Study-furnishing UI, the REST API, the
 * {@code wyrd voice} CLI, and the self-evolving Forge (phase 4) all share
 * one gate for read/write/freeze/revert semantics. Every mutation:
 *
 * <ol>
 *   <li>loads the latest {@link SoulManifest} for the DID,</li>
 *   <li>applies the change to the manifest's {@link VoiceProfile}
 *       (creating an empty one on first write),</li>
 *   <li>stores a new manifest version via {@link SoulStore#store}.</li>
 * </ol>
 *
 * <p>Thread-safety: delegates to the underlying {@link SoulStore}. Concurrent
 * writes against the same DID race — the last writer wins. This matches the
 * existing forge/sleep-pass pattern in {@link SoulMaintenanceCycle}.
 *
 * <p>Live-actor refresh: the CompanionActor caches the manifest and will
 * re-read it on the next forge tick. Callers who need immediate effect
 * should additionally message the companion to reload; the service does not
 * do this itself so it can be used from contexts (tests, CLI, offline
 * edits) where no actor system is available.
 *
 * <p> canonical (interim): voice profile lives
 * embedded in {@code SoulManifest.voiceProfile}. There is no
 * {@code voice_profiles} table yet. F7b Phase 2 will factor the
 * sub-record into its own table; this service becomes the boundary
 * between callers and that future table — keep all voice mutations
 * routed through here so the migration is one-class-deep.</p>
 */
public final class VoiceProfileService {

    private static final Logger log = LoggerFactory.getLogger(VoiceProfileService.class);

    private final SoulStore store;
    /**
     * optional canonical store.
     * When present, every mutation dual-writes (manifest field for
     * backward-compat readers + table as canonical source). When null
     * (legacy callers without DB), behaves as pre-Phase-2.1.
     */
    private final VoiceProfileStore voiceProfileStore;

    public VoiceProfileService(SoulStore store) {
        this(store, null);
    }

    /** F7b Phase 2.1: dual-write constructor. */
    public VoiceProfileService(SoulStore store, VoiceProfileStore voiceProfileStore) {
        this.store = Objects.requireNonNull(store, "store");
        this.voiceProfileStore = voiceProfileStore;
    }

    /**
     * Current voice profile for the given DID. Reads from the canonical
     * {@link VoiceProfileStore} if available; falls back to
     * {@code manifest.voiceProfile()} (backward-compat path) when the
     * table is missing or empty for this DID. F7b Phase 2.1.
     *
     * <p>Cross-zone behaviour: when a companion's manifest arrives in a
     * new zone (via SoulStore replication on relocation, or first-contact
     * during a visit), the new zone's voice_profiles table doesn't have
     * a row yet. The fallback serves reads correctly. To converge the
     * canonical store over time without a manual backfill task, we
     * <i>lazy-write</i> the fallback value into the table on first read
     * — idempotent, self-healing, no separate hook needed at the
     * relocation arrival path.
     */
    public Optional<VoiceProfile> get(String did) {
        // Canonical: voice_profiles table
        if (voiceProfileStore != null) {
            var fromStore = voiceProfileStore.load(did);
            if (fromStore.isPresent()) return fromStore;
        }
        // Fallback: embedded manifest field (pre-Phase-2.1 path, OR a
        // companion newly arrived in this zone via cross-zone relocation).
        var fromManifest = store.latest(did).map(m ->
            m.voiceProfile() != null ? m.voiceProfile() : VoiceProfile.empty());
        // F7b Phase 2.1: lazy-write the fallback into the canonical table
        // so the next read is rooted there. Skipped for empty profiles to
        // avoid spamming the table with rows for companions that have no
        // voice profile defined yet — those rows arrive on first mutation.
        if (voiceProfileStore != null && fromManifest.isPresent()
                && !fromManifest.get().clauses().isEmpty()) {
            voiceProfileStore.save(did, fromManifest.get());
            log.debug("VoiceProfileService: lazy-backfilled voice_profiles row for {}", did);
        }
        return fromManifest;
    }

    /**
     * Set or overwrite a single clause.
     *
     * @param did     Companion DID.
     * @param key     Clause key (e.g. "greeting-tone"). Must be non-blank.
     * @param value   Clause value. Must be non-blank (empty = use
     *                {@link #unsetClause} to remove).
     * @param reason  Short free-text reason recorded in history.
     * @param author  Who made the change (see
     *                {@link VoiceProfile.ProfileRevision#author}).
     * @return The updated profile.
     * @throws java.util.NoSuchElementException if no manifest exists for the DID.
     * @throws IllegalStateException            if the profile is frozen.
     * @throws IllegalArgumentException         if key or value is blank.
     */
    public VoiceProfile setClause(String did, String key, String value,
                                   String reason, String author) {
        requireNonBlank(key, "key");
        requireNonBlank(value, "value");
        return mutate(did, current -> {
            var next = new LinkedHashMap<>(current.clauses());
            next.put(key, value);
            return current.withClauses(next, reason, author);
        });
    }

    /**
     * Remove a clause by key. No-op (still records a revision) if the key
     * wasn't present — the history entry documents the intent either way.
     */
    public VoiceProfile unsetClause(String did, String key, String reason, String author) {
        requireNonBlank(key, "key");
        return mutate(did, current -> {
            var next = new LinkedHashMap<>(current.clauses());
            next.remove(key);
            return current.withClauses(next, reason, author);
        });
    }

    /**
     * Replace the entire clause set. Useful for bulk imports / Study UI
     * saves that send the full map. Empty map clears all clauses.
     */
    public VoiceProfile replaceClauses(String did, Map<String, String> clauses,
                                        String reason, String author) {
        Objects.requireNonNull(clauses, "clauses");
        return mutate(did, current -> current.withClauses(
            new LinkedHashMap<>(clauses), reason, author));
    }

    /**
     * Freeze the profile — blocks all future mutations until unfrozen.
     * Backwards-compat overload (default reason).
     */
    public VoiceProfile freeze(String did, String author) {
        return freeze(did, "frozen by " + (author != null ? author : "unknown"), author);
    }

    /**
     * Freeze with an explicit reason. Records a {@link ProfileRevision} so
     * the freeze event is visible in audit history. ultrareview bug_010.
     */
    public VoiceProfile freeze(String did, String reason, String author) {
        return mutateAllowFrozen(did,
            current -> current.withFrozen(true, reason, author));
    }

    /**
     * Unfreeze the profile. The frozen flag itself is not guarded.
     * Backwards-compat overload.
     */
    public VoiceProfile unfreeze(String did, String author) {
        return unfreeze(did, "unfrozen by " + (author != null ? author : "unknown"), author);
    }

    /**
     * Unfreeze with an explicit reason. Records a {@link ProfileRevision}
     * so the unfreeze event is visible in audit history.
     */
    public VoiceProfile unfreeze(String did, String reason, String author) {
        return mutateAllowFrozen(did,
            current -> current.withFrozen(false, reason, author));
    }

    /**
     * Revert the profile to the state at the given revision number.
     * Records the revert as a new history entry.
     *
     * @throws java.util.NoSuchElementException if targetRevision isn't in history.
     * @throws IllegalStateException            if the profile is frozen.
     */
    public VoiceProfile revertTo(String did, int targetRevision, String author) {
        return mutate(did, current -> current.revertTo(targetRevision, author)
            .orElseThrow(() -> new NoSuchElementException(
                "No revision " + targetRevision + " in voice profile history for " + did)));
    }

    // ─── Internals ─────────────────────────────────────────────────

    /** Mutation path that respects the frozen flag (profile-level writes). */
    private VoiceProfile mutate(String did, Function<VoiceProfile, VoiceProfile> fn) {
        var manifest = store.latest(did).orElseThrow(() ->
            new NoSuchElementException("No soul manifest for " + did));
        // F7b Phase 2.1: read canonical from table first, fall back to manifest.
        var current = readCurrentForMutation(did, manifest);
        var updated = fn.apply(current);
        // Dual-write: canonical table FIRST so the new value is durable
        // before the manifest blob is rewritten. If the manifest write
        // fails, the table is already correct; the next read sees the
        // truth even if the manifest blob lags.
        if (voiceProfileStore != null) {
            voiceProfileStore.save(did, updated);
        }
        store.store(manifest.withVoiceProfile(updated).bumpedVersion());
        log.info("Voice profile for {} bumped to revision {} (frozen={})",
            did, updated.revision(), updated.frozen());
        return updated;
    }

    /**
     * F7b Phase 2.1: source-of-truth read for in-mutation contexts.
     * Prefer the canonical table; fall back to the embedded manifest
     * field; default to empty when neither has data.
     */
    private VoiceProfile readCurrentForMutation(String did, SoulManifest manifest) {
        if (voiceProfileStore != null) {
            var fromStore = voiceProfileStore.load(did);
            if (fromStore.isPresent()) return fromStore.get();
        }
        return manifest.voiceProfile() != null
            ? manifest.voiceProfile() : VoiceProfile.empty();
    }

    /** Mutation path for flip-the-frozen-flag ops (must not be gated by it). */
    private VoiceProfile mutateAllowFrozen(String did, Function<VoiceProfile, VoiceProfile> fn) {
        var manifest = store.latest(did).orElseThrow(() ->
            new NoSuchElementException("No soul manifest for " + did));
        // F7b Phase 2.1: same canonical-first read pattern as mutate().
        var current = readCurrentForMutation(did, manifest);
        var updated = fn.apply(current);
        if (voiceProfileStore != null) {
            voiceProfileStore.save(did, updated);
        }
        store.store(manifest.withVoiceProfile(updated).bumpedVersion());
        log.info("Voice profile for {} frozen={} (revision {})",
            did, updated.frozen(), updated.revision());
        return updated;
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.SoulManifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-agent store of {@link Imprint}s.
 *
 * <h2>Retention</h2>
 * Default policy ({@link #DEFAULT_SELF_RETENTION} = 10):
 * <ul>
 *   <li>Keep the most recent {@code selfRetention} imprints with
 *       {@link Imprint.CreatedBy#SELF}.</li>
 *   <li>Keep all {@link Imprint.CreatedBy#AUTO_MILESTONE} imprints unconditionally.</li>
 *   <li>Keep all {@link Imprint.CreatedBy#USER_REQUEST} until the user explicitly
 *       {@link #delete deletes} one.</li>
 *   <li>Keep all {@link Imprint.CreatedBy#STEWARD_INTERVENTION} unconditionally
 *       (these are audit-worthy by definition).</li>
 * </ul>
 * Eviction runs after every {@link #imprint create} to keep storage bounded.
 *
 * <h2>Storage</h2>
 * In-memory today; persistence via the existing soul-vault infrastructure is
 * a concern of a later step. Size is tracked so that when the Vault is
 * introduced, totals are already available.
 *
 * <h2>Restore semantics (§10.4)</h2>
 * {@link #restore} returns the {@link SoulManifest} for the caller to apply
 * to the live agent. This class deliberately does <em>not</em> mutate the
 * agent or the journal — restoration is the caller's act. The journal lives
 * in {@link FamiliarJournal} and is untouched by restore, per spec.
 */
public final class ImprintManager {

    private static final Logger log = LoggerFactory.getLogger(ImprintManager.class);

    /** Default retention cap for SELF-created imprints. */
    public static final int DEFAULT_SELF_RETENTION = 10;

    /** Jackson mapper used only to size-estimate manifests. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String agentDid;
    private final int selfRetention;
    /** Keyed by imprint id. */
    private final ConcurrentMap<String, Imprint> store = new ConcurrentHashMap<>();

    public ImprintManager(String agentDid) {
        this(agentDid, DEFAULT_SELF_RETENTION);
    }

    public ImprintManager(String agentDid, int selfRetention) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        if (selfRetention < 1) selfRetention = DEFAULT_SELF_RETENTION;
        this.agentDid = agentDid;
        this.selfRetention = selfRetention;
    }

    // ── Create ─────────────────────────────────────────────────────────────

    /**
     * Create a new imprint. DID in {@code manifest} must match this manager's
     * {@code agentDid} — imprints are single-identity, not a family locker.
     */
    public Imprint imprint(Imprint.CreatedBy createdBy, String label, SoulManifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest required");
        if (!agentDid.equals(manifest.did())) {
            throw new IllegalArgumentException(
                "manifest.did (" + manifest.did() + ") does not match manager agentDid ("
                    + agentDid + ")");
        }
        var size = estimateSize(manifest);
        var imprint = Imprint.create(agentDid, createdBy,
            label == null ? "" : label, manifest, size);
        store.put(imprint.id(), imprint);
        var evicted = enforceRetention();
        if (!evicted.isEmpty()) {
            log.info("ImprintManager[{}] evicted {} SELF imprint(s) beyond retention cap {}",
                agentDid, evicted.size(), selfRetention);
        }
        return imprint;
    }

    /**
     * Insert an imprint that was loaded from disk, bypassing retention +
     * DID checks. Package-private — only {@link FamiliarPersistenceStore}
     * should call this during hydration.
     */
    void loadImprint(Imprint imprint) {
        if (imprint == null) return;
        store.put(imprint.id(), imprint);
    }

    // ── Restore ────────────────────────────────────────────────────────────

    /**
     * Fetch the manifest for a given imprint. The caller is responsible for
     * applying it to the live agent. The imprint itself remains in the store
     * — restore is non-destructive.
     */
    public SoulManifest restore(String imprintId) {
        var imprint = store.get(imprintId);
        if (imprint == null) {
            throw new NoSuchElementException("no imprint " + imprintId);
        }
        return imprint.manifest();
    }

    /** Restore the most recent imprint matching a predicate. */
    public Optional<Imprint> latestByCreator(Imprint.CreatedBy createdBy) {
        return store.values().stream()
            .filter(i -> i.createdBy() == createdBy)
            .max(Comparator.comparing(Imprint::createdAt));
    }

    /** Look up an imprint by its label (most-recent wins on duplicates). */
    public Optional<Imprint> byLabel(String label) {
        if (label == null || label.isBlank()) return Optional.empty();
        return store.values().stream()
            .filter(i -> label.equals(i.label()))
            .max(Comparator.comparing(Imprint::createdAt));
    }

    // ── Listing ────────────────────────────────────────────────────────────

    /** All imprints, newest first. */
    public List<Imprint> listAll() {
        return store.values().stream()
            .sorted(Comparator.comparing(Imprint::createdAt).reversed())
            .toList();
    }

    /** Imprints by creator, newest first. */
    public List<Imprint> byCreator(Imprint.CreatedBy createdBy) {
        return store.values().stream()
            .filter(i -> i.createdBy() == createdBy)
            .sorted(Comparator.comparing(Imprint::createdAt).reversed())
            .toList();
    }

    public Optional<Imprint> get(String imprintId) {
        return Optional.ofNullable(store.get(imprintId));
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    /** User-initiated delete. Returns whether the imprint existed. */
    public boolean delete(String imprintId) {
        return store.remove(imprintId) != null;
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    public int count() { return store.size(); }

    /** Sum of estimated sizes of all retained imprints. */
    public long totalSize() {
        return store.values().stream().mapToLong(Imprint::size).sum();
    }

    public String agentDid() { return agentDid; }

    // ── Retention ──────────────────────────────────────────────────────────

    /**
     * Drop SELF imprints beyond {@link #selfRetention}, keeping the newest.
     * Returns the evicted imprints.
     */
    private List<Imprint> enforceRetention() {
        var self = byCreator(Imprint.CreatedBy.SELF);
        if (self.size() <= selfRetention) return List.of();
        var evicted = new ArrayList<Imprint>();
        for (int i = selfRetention; i < self.size(); i++) {
            var victim = self.get(i);
            store.remove(victim.id());
            evicted.add(victim);
        }
        return Collections.unmodifiableList(evicted);
    }

    /**
     * Best-effort byte estimate. Uses Jackson on the manifest's profile and
     * resident identity — a fast subset that tends to dominate size. Falls
     * back to {@link SoulManifest#canonicalBytes} length if the full
     * serialization fails for any reason.
     */
    private static long estimateSize(SoulManifest manifest) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(manifest);
            return bytes.length;
        } catch (Exception e) {
            log.debug("size estimate fell back to canonical bytes: {}", e.getMessage());
            try {
                return manifest.canonicalBytes().length;
            } catch (Exception e2) {
                return 0;
            }
        }
    }
}

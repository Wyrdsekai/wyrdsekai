package org.wyrdsekai.core.story;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D.3 — arc registry.
 *
 * <p>Thread-safe in-memory store for declared and emergent arcs. Scenes
 * during a DECLARED arc's window auto-tag with the arcId at scene-close.
 * EMERGENT arcs land in a "proposed" state from the Forge sleep-pass and
 * are surfaced for human review.</p>
 *
 * <p>Persistence is the StoryStore's job — this registry is the in-memory
 * lookup. Boot loads from store; mutations persist through. The simple
 * separation keeps the pure-logic surface here clean.</p>
 */
public final class ArcRegistry {

    private final Map<String, Arc> arcs = new ConcurrentHashMap<>();
    private final Map<String, ProposedArc> proposed = new ConcurrentHashMap<>();

    /** A proposed emergent arc awaiting human review (accept / rename / reject). */
    public record ProposedArc(
        String id,
        String suggestedName,
        String focalEntityId,
        Instant proposedAt,
        List<String> sceneIds,
        Map<String, Object> criteria
    ) {
        public ProposedArc {
            if (id == null) throw new IllegalArgumentException("id required");
            sceneIds = sceneIds == null ? List.of() : List.copyOf(sceneIds);
            criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
        }
    }

    /**
     * Declare a new arc (DECLARED kind). Used by the player command
     * {@code :arc declare "name" until DATE} and by the agent action
     * {@code declare_arc}.
     *
     * @return the new arc
     */
    public Arc declare(String name, String focalEntityId,
                       Instant start, Instant end) {
        var arc = new Arc(UUID.randomUUID().toString(), name, ArcKind.DECLARED,
            focalEntityId,
            start == null ? Instant.now() : start,
            end, List.of(), Map.of());
        arcs.put(arc.id(), arc);
        return arc;
    }

    /**
     * Get all currently-open DECLARED arcs that contain {@code when} and
     * belong to {@code focalEntityId}. Used at scene-close to auto-tag.
     */
    public List<Arc> activeDeclaredArcs(String focalEntityId, Instant when) {
        if (focalEntityId == null || when == null) return List.of();
        var out = new ArrayList<Arc>();
        for (var arc : arcs.values()) {
            if (arc.kind() != ArcKind.DECLARED) continue;
            if (!focalEntityId.equals(arc.focalEntityId())) continue;
            if (arc.contains(when)) out.add(arc);
        }
        return Collections.unmodifiableList(out);
    }

    /** Look up an arc by id. */
    public Arc get(String id) {
        return arcs.get(id);
    }

    /** All known arcs (declared + accepted-emergent). */
    public List<Arc> all() {
        return List.copyOf(arcs.values());
    }

    /**
     * Add a scene to an arc's scene-id list. Used by StoryService when a
     * scene closes inside an active arc window (and at human-approve time
     * for emergent arcs).
     */
    public Arc tagScene(String arcId, String sceneId) {
        var existing = arcs.get(arcId);
        if (existing == null || sceneId == null) return existing;
        var combined = new ArrayList<>(existing.sceneIds());
        if (!combined.contains(sceneId)) combined.add(sceneId);
        var updated = new Arc(existing.id(), existing.name(), existing.kind(),
            existing.focalEntityId(), existing.rangeStart(), existing.rangeEnd(),
            combined, existing.criteria());
        arcs.put(arcId, updated);
        return updated;
    }

    /** Close a declared arc early (set rangeEnd). */
    public Arc close(String arcId, Instant when) {
        var existing = arcs.get(arcId);
        if (existing == null) return null;
        var updated = new Arc(existing.id(), existing.name(), existing.kind(),
            existing.focalEntityId(), existing.rangeStart(),
            when == null ? Instant.now() : when,
            existing.sceneIds(), existing.criteria());
        arcs.put(arcId, updated);
        return updated;
    }

    /** Replace an arc wholesale (used by StoryStore on persistence load). */
    public void put(Arc arc) {
        if (arc != null) arcs.put(arc.id(), arc);
    }

    // ─── Emergent (proposed) arc surface ─────────────────────────────────

    /**
     * Forge's sleep-pass clustering output. Adds a proposed emergent arc to
     * the review queue.
     */
    public ProposedArc propose(String suggestedName, String focalEntityId,
                                List<String> sceneIds,
                                Map<String, Object> criteria) {
        var p = new ProposedArc(UUID.randomUUID().toString(), suggestedName,
            focalEntityId, Instant.now(), sceneIds, criteria);
        proposed.put(p.id(), p);
        return p;
    }

    /** All proposed-and-not-yet-reviewed emergent arcs (for Chronicle UI). */
    public List<ProposedArc> proposedArcs() {
        return List.copyOf(proposed.values());
    }

    /** Look up a proposed arc by id. */
    public ProposedArc getProposed(String proposedId) {
        return proposed.get(proposedId);
    }

    /**
     * Human accepts a proposed arc (optionally with a renamed label). Moves
     * it into the canonical arc set as kind=EMERGENT. Returns the new arc,
     * or null if the proposed id is unknown.
     */
    public Arc accept(String proposedId, String chosenName) {
        var p = proposed.remove(proposedId);
        if (p == null) return null;
        var name = chosenName != null && !chosenName.isBlank() ? chosenName : p.suggestedName();
        var arc = new Arc(p.id(), name, ArcKind.EMERGENT, p.focalEntityId(),
            p.proposedAt(), null, p.sceneIds(), p.criteria());
        arcs.put(arc.id(), arc);
        return arc;
    }

    /**
     * Human rejects a proposed arc. Removes it from the queue and records a
     * suppression signature so Forge doesn't re-propose the same cluster.
     * The suppression set is in-memory only here; future versions wire it
     * through Forge persistence.
     */
    public boolean reject(String proposedId) {
        var removed = proposed.remove(proposedId);
        if (removed == null) return false;
        suppressedSignatures.add(signatureFor(removed.criteria()));
        return true;
    }

    /** Whether a cluster with this criteria signature has been suppressed. */
    public boolean isSuppressed(Map<String, Object> criteria) {
        return suppressedSignatures.contains(signatureFor(criteria));
    }

    private final Set<String> suppressedSignatures = ConcurrentHashMap.newKeySet();

    /**
     * Stable signature for a criteria map. Order-independent: sort keys
     * before concatenation. Used by reject() / isSuppressed() to identify
     * clusters by their characteristic features.
     */
    static String signatureFor(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) return "";
        var keys = new ArrayList<>(criteria.keySet());
        Collections.sort(keys);
        var sb = new StringBuilder();
        for (var k : keys) {
            sb.append(k).append('=').append(String.valueOf(criteria.get(k))).append('|');
        }
        return sb.toString();
    }

    /** Test seam: drop everything. */
    public void clear() {
        arcs.clear();
        proposed.clear();
        suppressedSignatures.clear();
    }

    /** Count of accepted arcs. */
    public int size() { return arcs.size(); }

    /** Count of proposed arcs awaiting review. */
    public int proposedSize() { return proposed.size(); }

    /** Build a HashMap of criteria for proposers; small convenience. */
    public static Map<String, Object> criteria(String key, Object value, Object... rest) {
        var m = new HashMap<String, Object>();
        m.put(key, value);
        for (int i = 0; i + 1 < rest.length; i += 2) {
            m.put(String.valueOf(rest[i]), rest[i + 1]);
        }
        return m;
    }
}

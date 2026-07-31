package org.wyrdsekai.core.story;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D.3 — an arc is a recurring thread spanning scenes.
 *
 * <p>Mirrors the structured-memory model:</p>
 * <ul>
 *   <li>{@link ArcKind#DECLARED} — focal entity declared it inline ("it's
 *       coding time"). Active from declaration → rangeEnd. Scenes during
 *       the window auto-tag with the arcId.</li>
 *   <li>{@link ArcKind#EMERGENT} — Forge sleep-pass recognized a cluster
 *       (≥5 scenes within window sharing focal want-class, participants,
 *       room, or object usage). Surfaces in Chronicle furnishing for human
 *       review (accept / rename / reject).</li>
 * </ul>
 *
 * @param id              UUID
 * @param name            human-readable label ("OSS release week", "the
 *                        bond with Lain")
 * @param kind            DECLARED or EMERGENT
 * @param focalEntityId   whose arc this is
 * @param rangeStart      inclusive start
 * @param rangeEnd        inclusive end; null = still open
 * @param sceneIds        scenes that belong to this arc
 * @param criteria        for EMERGENT: the cluster signature Forge used
 *                        ({@code participants}, {@code wantClass},
 *                        {@code rooms}, etc.). Empty for DECLARED.
 */
public record Arc(
    String id,
    String name,
    ArcKind kind,
    String focalEntityId,
    Instant rangeStart,
    Instant rangeEnd,
    List<String> sceneIds,
    Map<String, Object> criteria
) {
    public Arc {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Arc id required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Arc name required");
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (focalEntityId == null) throw new IllegalArgumentException("focalEntityId required");
        if (rangeStart == null) throw new IllegalArgumentException("rangeStart required");
        sceneIds = sceneIds == null ? List.of() : List.copyOf(sceneIds);
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    public boolean isOpen() { return rangeEnd == null; }

    public boolean contains(Instant t) {
        if (t == null) return false;
        if (t.isBefore(rangeStart)) return false;
        return rangeEnd == null || !t.isAfter(rangeEnd);
    }
}

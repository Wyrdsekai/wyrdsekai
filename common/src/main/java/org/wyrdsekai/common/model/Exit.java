package org.wyrdsekai.common.model;

import java.util.List;
import java.util.Optional;

/**
 * A navigable exit from a room.
 *
 * @param direction  Direction label (e.g. "north", "up", "portal")
 * @param targetRoom Target room ID
 * @param label      Human-readable description (e.g. "A narrow passage leads north")
 */
public record Exit(String direction, String targetRoom, String label) {

    /**
     * Resolve a player's {@code go <query>} against a room's exits. Exact direction match
     * first; then a normalized-contains fallback across direction, target-room id, and label,
     * so {@code go greenhouse} works when the exit is {@code to-greenhouse-7772 → Greenhouse}
     * (companion-created rooms register no compass direction or alias — second-node 2026-07-09).
     * The fuzzy pass requires ≥3 useful characters so {@code go n} can't mis-match.
     */
    public static Optional<Exit> resolve(List<Exit> exits, String query) {
        if (exits == null || query == null || query.isBlank()) return Optional.empty();
        var exact = exits.stream()
            .filter(e -> e.direction() != null && e.direction().equalsIgnoreCase(query))
            .findFirst();
        if (exact.isPresent()) return exact;
        var q = normalize(query);
        if (q.length() < 3) return Optional.empty();
        return exits.stream()
            .filter(e -> normalize(e.direction()).contains(q)
                || normalize(e.targetRoom()).contains(q)
                || normalize(e.label()).contains(q))
            .findFirst();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

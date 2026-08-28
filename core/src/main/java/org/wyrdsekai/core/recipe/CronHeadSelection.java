package org.wyrdsekai.core.recipe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Choosing what a scheduled retrain should work on.
 *
 * <p>Pure so it can be tested; the I/O (reading the manifest, the param overrides and the
 * run history) stays in the boot wiring. Split out because the choice is the part with
 * judgment in it, and judgment that lives inside a lambda in a 200-line boot method is
 * judgment nobody can check.
 */
final class CronHeadSelection {

    private CronHeadSelection() {}

    /** Parse a comma-separated candidate list; blank/absent means "no scheduled runs". */
    static List<String> parseCandidates(Object raw) {
        if (raw == null) return List.of();
        var out = new ArrayList<String>();
        for (var part : String.valueOf(raw).split(",")) {
            var t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * The candidate longest without a successful run.
     *
     * <p>Staleness rather than "worst score" because the recorded per-head accuracies are
     * not on a common scale: two of the shipped heads report train-set accuracy with
     * {@code validation_examples: 0}, so ranking by that number would call the unmeasured
     * heads perfect and starve the honestly-measured ones forever. Staleness also matches
     * what the recipe already says cron is for, and rotates fairly across the set.
     *
     * <p>A candidate that has never succeeded is the stalest of all; ties resolve to
     * declaration order, so the choice is deterministic.
     */
    static Optional<String> stalest(List<String> candidates, Map<String, Instant> lastSuccess) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        var history = lastSuccess == null ? Map.<String, Instant>of() : lastSuccess;
        String best = null;
        Instant bestAt = null;
        for (var candidate : candidates) {
            var at = history.get(candidate);
            if (at == null) return Optional.of(candidate);
            if (bestAt == null || at.isBefore(bestAt)) {
                bestAt = at;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}

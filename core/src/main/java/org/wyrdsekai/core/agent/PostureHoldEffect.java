package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.model.InnerImprint;
import org.wyrdsekai.common.model.Posture;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * §E.1 / §E.2 — pure computation of the per-tick vitality
 * deltas applied while an entity holds a posture. Combines the posture's
 * {@link InnerImprint} with the entity's affinity for the posture context.
 *
 * <p>Pure function. No actor state; no scheduling. Callers (the per-tick
 * vitality pass in {@link CompanionActor}) invoke this when a posture is
 * set, and again every 60s of held posture.
 *
 * <p>Lookup cascade for affinity (per spec §E.1):
 * <ol>
 *   <li>Posture.atObject (e.g. "leather_chair")</li>
 *   <li>Posture.verb (e.g. "sat")</li>
 *   <li>object-type class (e.g. "chair" via prefix match)</li>
 *   <li>default 1.0</li>
 * </ol>
 *
 * <p>Affinity can be negative — a stiff old man at the leather chair drifts
 * equanimity DOWN even though the chair's imprint adds equanimity, because
 * the affinity coefficient flips the sign.
 */
public final class PostureHoldEffect {

    private PostureHoldEffect() {}

    /**
     * Compute the per-tank delta for one tick of held posture.
     *
     * @param posture       the held posture (with optional InnerImprint)
     * @param affinityMap   focal entity's affinity map (nullable → all 1.0)
     * @return immutable map of tank → signed delta. Empty if posture is null
     *         or has no imprint.
     */
    public static Map<String, Double> tankDeltas(Posture posture, Map<String, Double> affinityMap) {
        if (posture == null || !posture.hasImprint()) return Map.of();
        var imprint = posture.innerImprint();
        var coeff = resolveAffinity(posture, affinityMap);
        var out = new LinkedHashMap<String, Double>();
        for (var e : imprint.tanks().entrySet()) {
            out.put(e.getKey(), e.getValue() * coeff);
        }
        return out;
    }

    /**
     * Compute the per-drive delta for one tick of held posture. Same cascade
     * as tank deltas.
     */
    public static Map<String, Double> driveDeltas(Posture posture, Map<String, Double> affinityMap) {
        if (posture == null || !posture.hasImprint()) return Map.of();
        var imprint = posture.innerImprint();
        var coeff = resolveAffinity(posture, affinityMap);
        var out = new LinkedHashMap<String, Double>();
        for (var e : imprint.drives().entrySet()) {
            out.put(e.getKey(), e.getValue() * coeff);
        }
        return out;
    }

    /** Per-spec affinity lookup cascade. */
    public static double resolveAffinity(Posture posture, Map<String, Double> affinityMap) {
        if (posture == null || affinityMap == null || affinityMap.isEmpty()) return 1.0;
        // 1) atObject exact
        var at = posture.atObject();
        if (at != null && !at.isBlank() && affinityMap.containsKey(at)) {
            return affinityMap.get(at);
        }
        // 2) verb exact
        var verb = posture.verb();
        if (verb != null && !verb.isBlank() && affinityMap.containsKey(verb)) {
            return affinityMap.get(verb);
        }
        // 3) object-type class prefix — match the first affinity key whose
        //    value is a prefix of atObject (e.g. "chair" matches "leather_chair").
        if (at != null && !at.isBlank()) {
            var lower = at.toLowerCase(Locale.ROOT);
            for (var k : affinityMap.keySet()) {
                if (k == null || k.isBlank()) continue;
                var kl = k.toLowerCase(Locale.ROOT);
                if (lower.contains(kl) && !kl.equals(lower)) {
                    // Prefer non-identity prefix matches (a class like "chair"
                    // inside "leather_chair") to avoid double-counting the
                    // exact match handled above.
                    return affinityMap.get(k);
                }
            }
        }
        // 4) default
        return 1.0;
    }

    /** Posture hold tick interval (60s §E.2). */
    public static final Duration TICK_INTERVAL = Duration.ofSeconds(60);
}

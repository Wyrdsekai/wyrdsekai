package org.wyrdsekai.core.forge;

import org.wyrdsekai.core.story.Scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * §E.1 — learned affinity drift from observed scene
 * tank-trajectory. Pure function; no actor state. Called from the Forge
 * sleep-pass over recently-closed scenes for a given focal entity.
 *
 * <p>Per scene: if the scene contains a posture-hold (focal entity sat at
 * something) and the focal's tank-trajectory across the hold was net
 * positive, drift the affinity for that posture-context up; if net
 * negative, drift down. Step size is intentionally tiny (0.05 per scene
 * reinforcement) so preferences emerge from sustained lived experience,
 * not from one hot scene.
 *
 * <p>Bounds: affinity stays in roughly [-1.5, 2.0]. Hard clamp at those
 * limits.
 *
 * <p>Key strategy mirrors the runtime lookup cascade
 * (SoulManifest.affinityMap docs):
 * <ol>
 *   <li>Posture.atObject (e.g. "leather_chair")</li>
 *   <li>Posture.verb (e.g. "sat")</li>
 *   <li>object-type class (e.g. "chair") — Forge doesn't infer this; runtime
 *       does. The learner stores by atObject when present, else verb.</li>
 * </ol>
 */
public final class AffinityLearner {

    /** Lower clamp on any single affinity value. */
    public static final double FLOOR = -1.5;
    /** Upper clamp on any single affinity value. */
    public static final double CEILING = 2.0;
    /** Per-scene reinforcement step. */
    public static final double STEP = 0.05;
    /** Drift target on positive trajectories. */
    public static final double POSITIVE_TARGET = 1.4;
    /** Drift target on negative trajectories. */
    public static final double NEGATIVE_TARGET = 0.6;
    /** Aggressive drift target on deeply negative trajectories. */
    public static final double DEEP_NEGATIVE_TARGET = 0.0;

    private AffinityLearner() {}

    /**
     * Trajectory observation for a single posture-hold inside one scene.
     * Forge synthesizes these from scene events + per-tick TankSnapshots
     * (Wave 9a-final, ResilienceSession). Pre-Forge versions can supply
     * a simple {@code netTankDelta} heuristic.
     *
     * @param postureKey    affinityMap key (Posture.atObject or fallback to verb)
     * @param netTankDelta  sum of tank deltas across the hold for the focal's
     *                      drives that matter (typically equanimity + soothing
     *                      + presence_load reversal). Positive = the hold was
     *                      good for the focal; negative = the hold drained.
     */
    public record HoldObservation(String postureKey, double netTankDelta) {}

    /**
     * Drift the affinity map by one sleep-pass over the given observations.
     * Returns a new map (does not mutate the input).
     *
     * @param current     current affinity map (nullable → treated as empty)
     * @param holds       per-scene posture-hold observations from recent scenes
     */
    public static Map<String, Double> drift(Map<String, Double> current,
                                              List<HoldObservation> holds) {
        var out = new LinkedHashMap<String, Double>();
        if (current != null) out.putAll(current);
        if (holds == null || holds.isEmpty()) return out;

        // Group observations by posture key, then apply step per group toward the
        // target derived from the group's signed net delta.
        var byKey = new HashMap<String, Double>();
        var countByKey = new HashMap<String, Integer>();
        for (var h : holds) {
            if (h.postureKey() == null || h.postureKey().isBlank()) continue;
            byKey.merge(h.postureKey(), h.netTankDelta(), Double::sum);
            countByKey.merge(h.postureKey(), 1, Integer::sum);
        }

        for (var entry : byKey.entrySet()) {
            var key = entry.getKey();
            var net = entry.getValue();
            int count = countByKey.getOrDefault(key, 1);
            var current_v = out.getOrDefault(key, 1.0);
            double target = net > 0
                ? POSITIVE_TARGET
                : (net < -0.5 ? DEEP_NEGATIVE_TARGET : NEGATIVE_TARGET);
            // Each observed scene contributes one STEP of drift.
            double newValue = current_v;
            for (int i = 0; i < count; i++) {
                newValue = newValue + STEP * (target - newValue);
            }
            newValue = Math.max(FLOOR, Math.min(CEILING, newValue));
            out.put(key, newValue);
        }
        return out;
    }

    /**
     * Convenience: extract HoldObservations from a list of recently-closed
     * scenes using a simple heuristic — scene's first felt blockquote (if
     * any) is ignored; this method just records the scene's wantContext
     * grouping and one observation per scene with the supplied net delta.
     *
     * <p>Production callers (the Forge sleep-pass) compute netTankDelta
     * from per-tick TankSnapshots captured during the hold; this helper
     * exists for tests and simple cases.
     */
    public static List<HoldObservation> observationsFromScenes(
            List<Scene> scenes,
            Function<Scene, Double> netDeltaProvider,
            Function<Scene, String> postureKeyProvider) {
        var out = new ArrayList<HoldObservation>();
        if (scenes == null) return out;
        for (var s : scenes) {
            var key = postureKeyProvider.apply(s);
            if (key == null) continue;
            var net = netDeltaProvider.apply(s);
            if (net == null) continue;
            out.add(new HoldObservation(key, net));
        }
        return out;
    }
}

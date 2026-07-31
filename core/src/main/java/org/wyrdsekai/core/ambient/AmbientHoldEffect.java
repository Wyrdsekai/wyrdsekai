package org.wyrdsekai.core.ambient;

import org.wyrdsekai.common.embodiment.AmbientImprint;
import org.wyrdsekai.common.embodiment.AmbientPhase;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pure per-tick vitality deltas applied while an
 * entity inhabits a room at a given ambient phase. Mirror of
 * {@code PostureHoldEffect} for ambient. Required (not optional) coupling —
 * "a body whose inner state doesn't know it's a body is half a body."
 *
 * <p>Pure function. No actor state, no scheduling. Callers invoke this on
 * the existing vitality tick cadence (every {@link #TICK_INTERVAL} of held
 * residency in a room) and feed the returned deltas through
 * {@code CompanionActor.applyTankDelta}.
 *
 * <p>Per §15.1 the magnitude lives in the same order as posture imprints
 * ({@code ~0.005-0.015/tick}); the ambient is texture, not a forcing
 * function. The room's tone + phase together choose the deltas; see
 * {@link AmbientRenderer#imprint}.
 */
public final class AmbientHoldEffect {

    private AmbientHoldEffect() {}

    /** Hold-tick cadence — matched to {@code PostureHoldEffect.TICK_INTERVAL} (60s). */
    public static final Duration TICK_INTERVAL = Duration.ofSeconds(60);

    /**
     * Compute the per-tank delta for one tick of holding residency in
     * {@code roomId} at {@code phase}. Empty map if no coupling applies.
     */
    public static Map<String, Double> tankDeltas(String roomId, AmbientPhase phase) {
        return tankDeltas(roomId, phase, null);
    }

    /**
     * Compute the per-tank delta with an optional affinity map. Per §E.1
     * (lookup cascade), the affinity is keyed by the room kind (e.g. {@code
     * library}, {@code chapel}) — a person who finds the chapel restful gets
     * a slightly bigger equanimity bump there. Affinity defaults to 1.0.
     */
    public static Map<String, Double> tankDeltas(String roomId, AmbientPhase phase,
                                                  Map<String, Double> affinityMap) {
        var imprint = AmbientRenderer.imprint(roomId, phase);
        if (imprint == null || imprint.isEmpty()) return Map.of();
        var coeff = resolveAffinity(roomId, affinityMap);
        if (coeff == 1.0) return imprint.tanks();
        var out = new LinkedHashMap<String, Double>(imprint.tanks().size());
        for (var e : imprint.tanks().entrySet()) out.put(e.getKey(), e.getValue() * coeff);
        return out;
    }

    /**
     * Compute drive deltas for one tick. Most ambient rules don't touch
     * drives in v1; this is here for parity and for future expansion.
     */
    public static Map<String, Double> driveDeltas(String roomId, AmbientPhase phase) {
        return driveDeltas(roomId, phase, null);
    }

    public static Map<String, Double> driveDeltas(String roomId, AmbientPhase phase,
                                                   Map<String, Double> affinityMap) {
        var imprint = AmbientRenderer.imprint(roomId, phase);
        if (imprint == null || imprint.drives().isEmpty()) return Map.of();
        var coeff = resolveAffinity(roomId, affinityMap);
        if (coeff == 1.0) return imprint.drives();
        var out = new LinkedHashMap<String, Double>(imprint.drives().size());
        for (var e : imprint.drives().entrySet()) out.put(e.getKey(), e.getValue() * coeff);
        return out;
    }

    /**
     * Resolve the entity's affinity for this room — cascade:
     * <ol>
     *   <li>Exact room-id match in affinityMap</li>
     *   <li>Foundation kind / provisioner kind via {@link AmbientRenderer#canonicalRoomKind}</li>
     *   <li>Default 1.0</li>
     * </ol>
     */
    public static double resolveAffinity(String roomId, Map<String, Double> affinityMap) {
        if (roomId == null || affinityMap == null || affinityMap.isEmpty()) return 1.0;
        var direct = affinityMap.get(roomId);
        if (direct != null) return direct;
        var kind = AmbientRenderer.canonicalRoomKind(roomId);
        if (kind != null && !kind.equals(roomId)) {
            var classMatch = affinityMap.get(kind);
            if (classMatch != null) return classMatch;
        }
        return 1.0;
    }
}

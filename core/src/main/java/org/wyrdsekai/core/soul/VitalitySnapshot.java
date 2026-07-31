package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.wyrdsekai.core.agent.DriveConfig;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.VitalityState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time snapshot of all vitality tanks (24 total: 20 runtime + 4 soul-only).
 * Originally 8-tank, expanded to 12 in Experiment 18 (MirrorResonance — added valence/safety/
 * resonance/curiosity), and to 24 in Phase 1A of (added integrity
 * and disgust to the runtime layer + 10 deprivation-shape runtime tanks).
 *
 * <p>Original runtime tanks (10): contextBudget, confidence, energy, alignment, errorPressure,
 * momentum, rapport, focus, integrity, disgust.
 * <p>Phase 1A runtime additions (10): restlessness, loneliness, stagnation, autonomyPressure,
 * significance, amae, saudade, obligation, harmony, standing.
 * <p>Soul-only extensions (4, snapshot-only — never on VitalityState): valence, safety,
 * resonance, curiosity.
 *
 * <p>VitalitySnapshot is the soul-layer representation. VitalityState is the runtime
 * representation. The two convert via fromState/toState.
 */
public record VitalitySnapshot(
    Map<String, Double> tanks,
    Instant capturedAt
) {
    /**
     * All tank names in canonical order. 24 = 20 runtime + 4 soul-extension.
     * Order matters: snapshot consumers iterate this list. Phase 1A appends the 10 new
     * deprivation-shape tanks after the original 10 runtime tanks, before the 4 soul-only
     * extensions.
     */
    public static final List<String> TANK_NAMES = List.of(
        // Original 10 runtime tanks (drive-shape).
        "contextBudget", "confidence", "energy", "alignment",
        "errorPressure", "momentum", "rapport", "focus",
        "integrity", "disgust",
        // Phase 1A runtime tanks (deprivation-shape).
        "restlessness", "loneliness", "stagnation", "autonomyPressure",
        "significance", "amae", "saudade", "obligation",
        "harmony", "standing",
        // Wave 1 runtime tank — Gilbert CFT soothing system receptor.
        "soothing",
        // Wave 1.5 runtime tanks — substrate-truth signal triad (with soothing).
        "allostaticLoad", "equanimity",
        // Soul-only extensions (validated in Experiment 18 — never on VitalityState).
        "valence", "safety", "resonance", "curiosity"
    );

    @JsonCreator
    public VitalitySnapshot(
        @JsonProperty("tanks") Map<String, Double> tanks,
        @JsonProperty("capturedAt") Instant capturedAt
    ) {
        this.tanks = Map.copyOf(tanks);
        this.capturedAt = capturedAt;
    }

    /** Create snapshot from runtime VitalityState (20 tanks) + extra soul-only defaults. */
    public static VitalitySnapshot fromState(VitalityState state) {
        return fromState(state, 0.5, 0.6, 0.5, 0.5);
    }

    /** Create snapshot with drive state included (for soul transfer / isekai). */
    public static VitalitySnapshot fromStateWithDrives(VitalityState state, DriveState drives) {
        var snap = fromState(state);
        var tanks = new LinkedHashMap<>(snap.tanks());
        // Add drive values to snapshot for transfer (10 entries — extends with startle/surprise).
        double[] d = drives.toArray();
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            tanks.put("drive." + DriveConfig.DRIVE_NAMES[i], d[i]);
        }
        return new VitalitySnapshot(tanks, Instant.now());
    }

    /** Extract drive state from snapshot (returns null if no drives present). */
    public DriveState extractDrives() {
        if (!tanks.containsKey("drive.seeking")) return null;
        double[] d = new double[DriveConfig.DRIVE_COUNT];
        for (int i = 0; i < DriveConfig.DRIVE_COUNT; i++) {
            d[i] = tanks.getOrDefault("drive." + DriveConfig.DRIVE_NAMES[i], 0.0);
        }
        return DriveState.fromArray(d);
    }

    /**
     * Create snapshot from runtime VitalityState with explicit soul-only extension values.
     * Phase 1A: maps all 20 runtime tanks (was 8 + 4 defaults; now 20 + 4 supplied).
     */
    public static VitalitySnapshot fromState(VitalityState state,
                                              double valence, double safety,
                                              double resonance, double curiosity) {
        var tanks = new LinkedHashMap<String, Double>();
        // Original 10 runtime tanks.
        tanks.put("contextBudget", state.contextBudget());
        tanks.put("confidence", state.confidence());
        tanks.put("energy", state.energy());
        tanks.put("alignment", state.alignment());
        tanks.put("errorPressure", state.errorPressure());
        tanks.put("momentum", state.momentum());
        tanks.put("rapport", state.rapport());
        tanks.put("focus", state.focus());
        tanks.put("integrity", state.integrity());
        tanks.put("disgust", state.disgust());
        // Phase 1A runtime tanks (10).
        tanks.put("restlessness", state.restlessness());
        tanks.put("loneliness", state.loneliness());
        tanks.put("stagnation", state.stagnation());
        tanks.put("autonomyPressure", state.autonomyPressure());
        tanks.put("significance", state.significance());
        tanks.put("amae", state.amae());
        tanks.put("saudade", state.saudade());
        tanks.put("obligation", state.obligation());
        tanks.put("harmony", state.harmony());
        tanks.put("standing", state.standing());
        // Wave 1 soothing tank.
        tanks.put("soothing", state.soothing());
        // Wave 1.5 substrate-truth tanks.
        tanks.put("allostaticLoad", state.allostaticLoad());
        tanks.put("equanimity", state.equanimity());
        // Soul-only extensions.
        tanks.put("valence", valence);
        tanks.put("safety", safety);
        tanks.put("resonance", resonance);
        tanks.put("curiosity", curiosity);
        return new VitalitySnapshot(tanks, Instant.now());
    }

    /** Get a tank value by name, with default. */
    public double tank(String name) {
        return tanks.getOrDefault(name, 0.5);
    }

    /** Create snapshot from most recent entry in history, or defaults if empty. */
    public static VitalitySnapshot fromHistory(List<VitalitySnapshot> history) {
        if (history == null || history.isEmpty()) return defaults();
        return history.getLast();
    }

    /** Default snapshot — original tanks at moderate values, Phase 1A tanks at 0.0. */
    public static VitalitySnapshot defaults() {
        var tanks = new LinkedHashMap<String, Double>();
        // Original 10 runtime tanks.
        tanks.put("contextBudget", 0.5);
        tanks.put("confidence", 0.5);
        tanks.put("energy", 1.0);
        tanks.put("alignment", 0.5);
        tanks.put("errorPressure", 0.0);
        tanks.put("momentum", 0.4);
        tanks.put("rapport", 0.5);
        tanks.put("focus", 0.5);
        tanks.put("integrity", 0.7);
        tanks.put("disgust", 0.0);
        // Phase 1A: deprivation-shape tanks default to 0.0 (nothing felt yet).
        tanks.put("restlessness", 0.0);
        tanks.put("loneliness", 0.0);
        tanks.put("stagnation", 0.0);
        tanks.put("autonomyPressure", 0.0);
        tanks.put("significance", 0.0);
        tanks.put("amae", 0.0);
        tanks.put("saudade", 0.0);
        tanks.put("obligation", 0.0);
        tanks.put("harmony", 0.0);
        tanks.put("standing", 0.0);
        // Wave 1 soothing tank — mild baseline (Gilbert: at rest, neither
        // activated nor suppressed).
        tanks.put("soothing", 0.3);
        // Wave 1.5 substrate-truth tanks. Allostatic load starts at zero (no
        // damage yet); equanimity at 0.2 (mild capacity, grows through practice).
        tanks.put("allostaticLoad", 0.0);
        tanks.put("equanimity", 0.2);
        // Soul-only extensions.
        tanks.put("valence", 0.5);
        tanks.put("safety", 0.6);
        tanks.put("resonance", 0.5);
        tanks.put("curiosity", 0.5);
        return new VitalitySnapshot(tanks, Instant.now());
    }
}

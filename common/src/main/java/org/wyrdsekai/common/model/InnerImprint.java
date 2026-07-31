package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * the optional inner-state coupling carried by a {@link Posture}.
 *
 * <p>Per-tick effects an embodied state has on the entity's vitality and drive system,
 * plus an optional one-shot signal on posture entry. v1 is a small additive map applied
 * by the existing vitality update path; v2 will expand this to
 * continuous-time forcing functions.</p>
 *
 * <p>The world doesn't only produce biography — it produces physiology. A body whose inner
 * state doesn't know it's a body is half a body.</p>
 *
 * @param tanks          per-tick vitality-tank deltas while this posture is held
 *                       (e.g. {@code {"equanimity": 0.02, "energy": 0.005}})
 * @param drives         per-tick drive deltas while this posture is held
 *                       (e.g. {@code {"care_pang": 0.03}})
 * @param triggersOnSet  optional internal-event name fired once when the posture is set
 *                       (e.g. {@code "settled"} — readable by the drive-OODA loop)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InnerImprint(
    @JsonProperty("tanks") Map<String, Double> tanks,
    @JsonProperty("drives") Map<String, Double> drives,
    @JsonProperty("triggersOnSet") String triggersOnSet
) {

    /** Empty imprint (no per-tick effect, no triggers). Useful default. */
    public static final InnerImprint NONE = new InnerImprint(Map.of(), Map.of(), null);

    public InnerImprint {
        tanks = tanks == null ? Map.of() : Map.copyOf(tanks);
        drives = drives == null ? Map.of() : Map.copyOf(drives);
    }

    /** Convenience: only tank deltas, no drive deltas, no trigger. */
    public static InnerImprint ofTanks(Map<String, Double> tanks) {
        return new InnerImprint(tanks, Map.of(), null);
    }

    /** Convenience: tanks + a one-shot trigger event on posture set. */
    public static InnerImprint ofTanks(Map<String, Double> tanks, String triggersOnSet) {
        return new InnerImprint(tanks, Map.of(), triggersOnSet);
    }

    @JsonCreator
    public static InnerImprint create(
            @JsonProperty("tanks") Map<String, Double> tanks,
            @JsonProperty("drives") Map<String, Double> drives,
            @JsonProperty("triggersOnSet") String triggersOnSet) {
        return new InnerImprint(
            tanks != null ? tanks : Map.of(),
            drives != null ? drives : Map.of(),
            triggersOnSet);
    }

    /** True when this imprint produces no effects — equivalent to NONE. */
    public boolean isEmpty() {
        return tanks.isEmpty() && drives.isEmpty() && triggersOnSet == null;
    }
}

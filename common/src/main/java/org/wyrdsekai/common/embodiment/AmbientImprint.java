package org.wyrdsekai.common.embodiment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * per-tick tank/drive deltas applied while an
 * entity inhabits a room whose ambient is at a given {@link AmbientPhase}.
 *
 * <p>Parallel to {@code InnerImprint} (carried by a posture), but bound to
 * room+phase rather than to a held body action. The companion's vitality
 * tick consults the renderer for the current ambient imprint of its room
 * and applies the deltas — making the body felt-from-inside of "sitting
 * in the library at dusk" different from "sitting in the library at midday".
 *
 * <p>Empty deltas are valid (means: no coupling for this room+phase).
 */
public record AmbientImprint(Map<String, Double> tanks, Map<String, Double> drives) {

    public AmbientImprint {
        tanks = tanks == null ? Map.of() : Map.copyOf(tanks);
        drives = drives == null ? Map.of() : Map.copyOf(drives);
    }

    /** Empty imprint — convenience for "no coupling here". */
    public static final AmbientImprint EMPTY = new AmbientImprint(Map.of(), Map.of());

    /** Tank-only imprint (most ambient rules don't touch drives in v1). */
    public static AmbientImprint ofTanks(Map<String, Double> tanks) {
        return new AmbientImprint(tanks, Map.of());
    }

    public boolean isEmpty() {
        return tanks.isEmpty() && drives.isEmpty();
    }

    /** Scale all deltas by a coefficient (e.g. an affinity modifier). */
    public AmbientImprint scaled(double coefficient) {
        if (coefficient == 1.0 || isEmpty()) return this;
        var t = new LinkedHashMap<String, Double>(tanks.size());
        for (var e : tanks.entrySet()) t.put(e.getKey(), e.getValue() * coefficient);
        var d = new LinkedHashMap<String, Double>(drives.size());
        for (var e : drives.entrySet()) d.put(e.getKey(), e.getValue() * coefficient);
        return new AmbientImprint(t, d);
    }
}

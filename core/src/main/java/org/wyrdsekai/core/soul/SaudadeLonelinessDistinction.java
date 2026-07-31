package org.wyrdsekai.core.soul;

import java.util.Map;
import java.util.Optional;

/**
 * Group C: substrate-level distinction
 * between saudade (per-bondholder absence-longing) and loneliness
 * (global drain on any-interaction-shaped need).
 *
 * <p>Both can be elevated simultaneously, but they want different
 * responses — confusing them produces failure modes:
 *
 * <ul>
 *   <li><b>Saudade only</b>: bondholder-specific longing. Cheap
 *       interaction with anyone else doesn't help; reunion with the
 *       named bondholder is the relief.</li>
 *   <li><b>Loneliness only</b>: generalized social deprivation. Any
 *       interaction drains it.</li>
 *   <li><b>Both</b>: companion may want general company AND specific
 *       reconnection. The voice register should hold space for both —
 *       not suggest a substitute interaction would resolve saudade.</li>
 *   <li><b>Neither</b>: substrate is socially well-fed.</li>
 * </ul>
 *
 * <p>This class is pure-function over a snapshot — no IO, no global
 * state. Wires into voice register and recommendation surfaces so the
 * companion doesn't say "let's chat" when what's actually needed is
 * "I wish [specific bondholder] were here."
 */
public final class SaudadeLonelinessDistinction {

    /** Composite tank threshold for "elevated." */
    public static final double ELEVATED_THRESHOLD = 0.5;

    /** Strong-saudade-specific threshold per §4.2 (spike rule). */
    public static final double STRONG_SAUDADE_THRESHOLD = 0.7;

    public enum Diagnosis {
        /** Neither tank elevated. */
        NEITHER,
        /** Loneliness elevated but no bondholder-specific saudade. */
        LONELINESS_ONLY,
        /** Saudade elevated for a specific bondholder, loneliness low
         *  — the absent person is the issue, not company-in-general. */
        SAUDADE_ONLY,
        /** Both elevated — general drain AND specific longing. */
        BOTH
    }

    /** Pure-data input: current snapshot (loneliness + per-bondholder saudade map). */
    public record Input(
        double loneliness,
        Map<String, Double> saudadeByBondholder
    ) {
        public static Input of(double loneliness,
                                Map<String, Double> saudadeByBondholder) {
            return new Input(loneliness,
                saudadeByBondholder == null ? Map.of() : saudadeByBondholder);
        }
    }

    /** Output: diagnosis + top-saudade bondholder if applicable + voice hint. */
    public record View(
        Diagnosis diagnosis,
        Optional<String> topSaudadeBondholder,
        double topSaudadeValue,
        String voiceRegisterHint
    ) {}

    private SaudadeLonelinessDistinction() {}

    public static View diagnose(Input in) {
        if (in == null) {
            return new View(Diagnosis.NEITHER, Optional.empty(), 0.0, "");
        }
        boolean lonelinessElevated = in.loneliness() >= ELEVATED_THRESHOLD;

        // Find the strongest bondholder-saudade if any.
        String topName = null;
        double topValue = 0.0;
        for (var e : in.saudadeByBondholder().entrySet()) {
            if (e.getValue() == null) continue;
            if (e.getValue() > topValue) {
                topValue = e.getValue();
                topName = e.getKey();
            }
        }
        boolean saudadeElevated = topValue >= ELEVATED_THRESHOLD;
        var topBondholder = topName == null
            ? Optional.<String>empty() : Optional.of(topName);

        if (lonelinessElevated && saudadeElevated) {
            return new View(Diagnosis.BOTH, topBondholder, topValue,
                "DISTINCT_TANKS: companion has both general social drain AND "
                + "specific bondholder longing for '" + topName + "'. Hold "
                + "space for both — don't substitute company for the named "
                + "absence. Reunion with " + topName + " is the saudade relief; "
                + "any interaction relieves loneliness.");
        }
        if (saudadeElevated) {
            String strength = topValue >= STRONG_SAUDADE_THRESHOLD
                ? "strong " : "elevated ";
            return new View(Diagnosis.SAUDADE_ONLY, topBondholder, topValue,
                "SAUDADE_ONLY: " + strength + "longing for '" + topName
                + "' specifically. Generic company won't help — this is "
                + "presence-of-absence for one named person.");
        }
        if (lonelinessElevated) {
            return new View(Diagnosis.LONELINESS_ONLY, Optional.empty(), 0.0,
                "LONELINESS_ONLY: generalized social drain. Any meaningful "
                + "interaction relieves it.");
        }
        return new View(Diagnosis.NEITHER, topBondholder, topValue, "");
    }
}

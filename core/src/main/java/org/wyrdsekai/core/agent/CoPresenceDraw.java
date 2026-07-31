package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Pure approach-appetite (social-draw) curve for the co-presence homeostat, extracted from
 * {@code CompanionActor.applyCoPresenceRelief} so the drive math can be soak-tested without the
 * 4B / a live actor. Deterministic: {@code draw = familiarity × stalenessCurve(sinceEngaged)}.
 *
 * <h2>The co-presence-loop cure (2026-06-17)</h2>
 * The original curve let staleness (hence draw) begin rebuilding the instant a peer was engaged,
 * over {@code SOCIAL_DRAW_REBUILD_SECONDS}. After hearing/reaching a peer the draw therefore
 * recovered above the reach-threshold on a fixed metronome and re-fired forever — two content
 * companions ping-ponging near-verbatim sleepy chatter. The receive-side satiation alone could
 * not hold: it only reset the clock, it did not impose any quiet window.
 *
 * <p>This curve adds a firm {@link #REFRACTORY_SECONDS} post-engagement floor: for that long after
 * the last genuine engagement the draw is forced to <b>zero</b> (companionable quiet — the scene
 * settles). Only AFTER the refractory does staleness begin to rebuild, over {@code rebuildSeconds},
 * so a <i>genuinely</i> stale bond (peer hasn't engaged in a long while) still eventually surfaces
 * ONE reach. It is the <i>infinite near-verbatim re-reach</i> that this kills, not reaching itself.
 */
public final class CoPresenceDraw {

    private CoPresenceDraw() {}

    /**
     * Firm post-engagement quiet window (real seconds). Within it the approach-appetite toward a
     * just-engaged peer is held at zero so a content pair settles into companionable quiet instead
     * of re-reaching every cycle. Chosen comfortably longer than the rebuild ramp so the appetite
     * cannot recover above {@code SOCIAL_DRAW_THRESHOLD} the moment the ramp would otherwise allow.
     * Soak/Study-tunable; default 1200s (~20 min) of quiet after a real exchange — a settled pair
     * then re-contacts only every ~20 min (a gentle "still here"), not on the OODA cadence.
     */
    public static final double REFRACTORY_SECONDS = parseEnv(
        "WYRD_SOCIAL_DRAW_REFRACTORY_SECONDS", 1200.0);

    private static double parseEnv(String key, double fallback) {
        var raw = System.getenv(key);
        if (raw == null) raw = System.getProperty(key.toLowerCase(Locale.ROOT)
            .replace('_', '.'));
        if (raw == null) return fallback;
        try {
            double v = Double.parseDouble(raw.trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Staleness ∈ [0,1] for a peer last engaged at {@code lastEngaged} (null = never engaged →
     * fully stale, 1.0). Zero throughout the refractory window, then a linear ramp to 1.0 over
     * {@code rebuildSeconds} of further elapsed time.
     */
    public static double staleness(Instant lastEngaged, Instant now, double rebuildSeconds) {
        if (lastEngaged == null) return 1.0;
        double sinceSec = Duration.between(lastEngaged, now).toMillis() / 1000.0;
        if (sinceSec <= REFRACTORY_SECONDS) return 0.0;   // companionable quiet — held down
        if (rebuildSeconds <= 0) return 1.0;
        double ramp = (sinceSec - REFRACTORY_SECONDS) / rebuildSeconds;
        return Math.max(0.0, Math.min(1.0, ramp));
    }

    /** Approach-appetite draw toward a peer: {@code familiarity × staleness}. */
    public static double draw(double familiarity, Instant lastEngaged, Instant now,
            double rebuildSeconds) {
        return familiarity * staleness(lastEngaged, now, rebuildSeconds);
    }
}

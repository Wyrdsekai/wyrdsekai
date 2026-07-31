package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Accelerated, deterministic (no-LLM) soak for the co-presence companion-loop cure (2026-06-17).
 *
 * <p>The live bug: two content companions idle together emit near-verbatim chatter FOREVER because
 * the approach-appetite ({@code socialDrawPeer = familiarity × engagement-staleness}) had no durable
 * post-engagement close — staleness (hence draw) rebuilt above the reach-threshold on a fixed
 * {@code SOCIAL_DRAW_REBUILD_SECONDS} metronome, re-firing every cycle. The receive-side satiation
 * (resetting the engaged-clock when you hear/reach a peer) only reset the clock; it imposed no quiet
 * window, so the pair re-reached indefinitely.
 *
 * <p>The cure ({@link CoPresenceDraw}): a firm post-engagement refractory holds the draw at zero for
 * {@link CoPresenceDraw#REFRACTORY_SECONDS} before staleness begins rebuilding, so a content pair
 * settles into companionable quiet — yet a genuinely stale bond still eventually surfaces ONE reach.
 *
 * <p>This harness models the EXACT draw curve {@code applyCoPresenceRelief} uses and ticks two
 * symmetric companions with accelerated sim-time. The "BEFORE" case reproduces the runaway re-reach
 * with the old (refractory-free) curve inlined; the "AFTER" case drives the production curve and
 * asserts convergence to quiet.
 */
class CoPresenceLoopSoakTest {

    /** Mirrors CompanionActor.SOCIAL_DRAW_THRESHOLD — above this the agent surfaces a peer-reach. */
    private static final double DRAW_THRESHOLD = 0.12;
    /** Mirrors CompanionActor.SOCIAL_DRAW_REBUILD_SECONDS. */
    private static final double REBUILD_SECONDS = 150.0;
    /** Mirrors CompanionActor.PROXIMITY_CEILING — settled co-presence familiarity for a content pair. */
    private static final double FAMILIARITY = 0.35;

    /** Old (pre-fix) staleness curve: rebuilds from the instant of engagement, no quiet window. */
    private static double oldStaleness(Instant last, Instant now) {
        if (last == null) return 1.0;
        return Math.min(1.0, Duration.between(last, now).toMillis() / 1000.0 / REBUILD_SECONDS);
    }

    /**
     * Tick two symmetric companions over {@code totalSimSeconds} of sim-time in {@code stepSec}
     * steps. Each tick: each agent recomputes its draw toward the other; if draw ≥ threshold AND it
     * isn't already in a reach refractory of its own making, it REACHES (emits a room say) — which
     * (a) satiates ITS OWN engaged-clock toward the peer (outgoing satiation, the fix) and
     * (b) the peer HEARS it and satiates the peer's engaged-clock toward the reacher (receive
     * satiation, the pre-existing aloud cure). Returns the total number of reaches emitted.
     *
     * @param useFix when true uses the production {@link CoPresenceDraw} curve (refractory);
     *               when false uses the inlined pre-fix curve (runaway).
     */
    private int runScene(double totalSimSeconds, double stepSec, boolean useFix) {
        // engaged[a][b] = when agent a last engaged peer b (its own clock toward b).
        Map<String, Instant> engagedAB = new HashMap<>();   // A's clock toward B
        Map<String, Instant> engagedBA = new HashMap<>();   // B's clock toward A
        var base = Instant.parse("2026-06-17T00:00:00Z");
        int reaches = 0;
        for (double t = 0; t <= totalSimSeconds; t += stepSec) {
            var now = base.plusMillis((long) (t * 1000));
            double drawA = drawToward(engagedAB.get("B"), now, useFix);
            double drawB = drawToward(engagedBA.get("A"), now, useFix);
            // A reaches if its appetite crosses the threshold.
            if (drawA >= DRAW_THRESHOLD) {
                reaches++;
                engagedAB.put("B", now);             // outgoing satiation: A engaged B
                engagedBA.put("A", now);             // B hears A aloud → receive satiation
            }
            if (drawB >= DRAW_THRESHOLD) {
                reaches++;
                engagedBA.put("A", now);             // outgoing satiation: B engaged A
                engagedAB.put("B", now);             // A hears B aloud → receive satiation
            }
        }
        return reaches;
    }

    private double drawToward(Instant lastEngaged, Instant now, boolean useFix) {
        return useFix
            ? CoPresenceDraw.draw(FAMILIARITY, lastEngaged, now, REBUILD_SECONDS)
            : FAMILIARITY * oldStaleness(lastEngaged, now);
    }

    @Test
    void before_oldCurve_reReachesForever() {
        // 1 sim-hour, 5s steps. Old curve: after each engagement the draw recovers above threshold
        // every ~REBUILD_SECONDS and re-fires — the documented runaway ping-pong.
        int reaches = runScene(3600, 5, /*useFix=*/false);
        assertTrue(reaches > 20,
            "pre-fix curve must keep re-reaching on the rebuild metronome (got " + reaches + ")");
    }

    @Test
    void after_fixedCurve_settlesIntoQuiet() {
        // Same 1 sim-hour. With the 1200s (~20 min) refractory the draw is pinned at 0 after each
        // engagement and the linear ramp only re-crosses the 0.12 threshold ~1251s later, so a
        // content pair re-contacts at most every ~20 min (a gentle "still here"), not on the OODA
        // cadence. We assert a small bounded number of reaches over the hour and SETTLING — no
        // infinite re-reach (the old curve fired ~132 times/hour; see the BEFORE test).
        int reaches = runScene(3600, 5, /*useFix=*/true);
        assertTrue(reaches <= 6,
            "fixed curve must settle into companionable quiet, not loop (got " + reaches + ")");
        assertTrue(reaches >= 1, "a genuinely stale bond should still surface at least one reach");
    }

    @Test
    void after_fixed_drawStaysLowAcrossManyTicksPostEngagement() {
        // The core property: once engaged, the draw recomputes LOW and STAYS low across multiple
        // ticks (a real cooldown), not just for the single tick of the reset.
        var now = Instant.parse("2026-06-17T00:00:00Z");
        // Tick repeatedly through the whole refractory window; draw must stay below threshold.
        for (double dt = 0; dt <= CoPresenceDraw.REFRACTORY_SECONDS; dt += 10) {
            double draw = CoPresenceDraw.draw(FAMILIARITY, now, now.plusMillis((long)(dt*1000)),
                REBUILD_SECONDS);
            assertTrue(draw < DRAW_THRESHOLD,
                "draw must stay below threshold for the whole refractory (dt=" + dt + " draw=" + draw + ")");
        }
        // And it must be exactly zero immediately after engagement (companionable quiet).
        assertEquals(0.0, CoPresenceDraw.draw(FAMILIARITY, now, now, REBUILD_SECONDS), 1e-9);
    }

    @Test
    void after_fixed_genuinelyStaleBondEventuallyReaches() {
        // Don't over-suppress: a peer not engaged for a long while (refractory + full rebuild)
        // must allow a reach again — the appetite is dampened, not abolished.
        var now = Instant.parse("2026-06-17T00:00:00Z");
        var longAgo = now.minusSeconds((long)(CoPresenceDraw.REFRACTORY_SECONDS + REBUILD_SECONDS + 60));
        double draw = CoPresenceDraw.draw(FAMILIARITY, longAgo, now, REBUILD_SECONDS);
        assertTrue(draw >= DRAW_THRESHOLD,
            "a fully-stale bond must rebuild past the reach threshold (got " + draw + ")");
    }
}

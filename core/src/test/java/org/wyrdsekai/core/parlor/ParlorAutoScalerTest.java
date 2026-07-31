package org.wyrdsekai.core.parlor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ParlorAutoScalerTest {

    private static final Instant T0 = Instant.parse("2026-04-19T12:00:00Z");

    // ── within-band (no change) ───────────────────────────────────────

    @Test void withinFullBand_noChange() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FULL, 5, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
        assertEquals(ParlorPresenceMode.FULL,
            ((ParlorAutoScaler.Decision.NoChange) d).current());
    }

    @Test void withinSampledBand_noChange() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 20, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
    }

    // ── UP transitions (immediate, no dwell) ──────────────────────────

    @Test void fullToSampled_immediate() {
        // Occupancy hits 11 (SAMPLED upThreshold) → transition up right away.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FULL, 11, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
        var t = (ParlorAutoScaler.Decision.Transition) d;
        assertEquals(ParlorPresenceMode.FULL, t.from());
        assertEquals(ParlorPresenceMode.SAMPLED, t.to());
        assertTrue(t.narration().toLowerCase().contains("busier")
            || t.narration().toLowerCase().contains("voices"),
            "expected diegetic narration, got: " + t.narration());
    }

    @Test void sampledToFirehose_skipsIntermediate() {
        // A sudden rush can skip SAMPLED_STRICT entirely — 20 → 150 in one
        // reading should move directly to FIREHOSE, not stage through.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 150, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
        var t = (ParlorAutoScaler.Decision.Transition) d;
        assertEquals(ParlorPresenceMode.FIREHOSE, t.to());
    }

    @Test void upTransition_ignoresDwell() {
        // Even if lastChangeAt is recent (within dwell window), UP fires.
        // Safety-critical: don't silence a room while voices are still rising.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FULL, 11, T0, T0.plusSeconds(1));
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
    }

    // ── DOWN transitions (dwelled) ────────────────────────────────────

    @Test void sampledToFull_requiresDwell() {
        // Occupancy drops from 15 to 7 (below downThreshold=8). Only 30s
        // have elapsed — dwell not satisfied, must stay SAMPLED.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 7, T0, T0.plusSeconds(30));
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
    }

    @Test void sampledToFull_afterDwell() {
        // Same drop, but 61s later — dwell window satisfied → transition.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 7, T0, T0.plusSeconds(61));
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
        var t = (ParlorAutoScaler.Decision.Transition) d;
        assertEquals(ParlorPresenceMode.FULL, t.to());
        assertTrue(t.narration().toLowerCase().contains("quiet")
            || t.narration().toLowerCase().contains("return"),
            "DOWN narration should read as 'quieting': " + t.narration());
    }

    @Test void hysteresisBand_preventsFlapping() {
        // Occupancy at 10 (inside SAMPLED's hold band: downThreshold=8 means
        // stay-sampled until we drop to ≤ 8). 10 is > 8, so no change even
        // after long dwell.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 10, T0, T0.plusSeconds(300));
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
    }

    @Test void hysteresisBand_firehoseHold() {
        // FIREHOSE holds until 98. At 99, stay FIREHOSE.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FIREHOSE, 99, T0, T0.plusSeconds(300));
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
    }

    @Test void firehoseDownToStrict_afterDwell() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FIREHOSE, 98,
            T0, T0.plusSeconds(61));
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT,
            ((ParlorAutoScaler.Decision.Transition) d).to());
    }

    @Test void downTransition_skipsIntermediate() {
        // Drop from SAMPLED_STRICT (30-ish) straight to 3. Dwell satisfied.
        // Target mode is FULL (skipping SAMPLED). One hop is correct —
        // re-issuing a second transition on the next tick would stagger UI.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED_STRICT, 3,
            T0, T0.plusSeconds(61));
        assertInstanceOf(ParlorAutoScaler.Decision.Transition.class, d);
        var t = (ParlorAutoScaler.Decision.Transition) d;
        assertEquals(ParlorPresenceMode.FULL, t.to());
    }

    @Test void downTransition_withNullLastChangeAt_usesLongestDwell() {
        // If lastChangeAt is null (parlor never transitioned), we can't
        // compute a dwell interval — treat as "not yet eligible". Caller
        // should pass creation time; this branch is a safety net.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 5, null, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.NoChange.class, d);
    }

    // ── DoS cap ───────────────────────────────────────────────────────

    @Test void atCap_returnsAtCapDecision() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FIREHOSE, 501, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.AtCap.class, d);
        var c = (ParlorAutoScaler.Decision.AtCap) d;
        assertEquals(1, c.over());
        assertEquals(ParlorPresenceMode.FIREHOSE, c.current());
    }

    @Test void atCap_countsOverage() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FIREHOSE, 700, T0, T0);
        assertInstanceOf(ParlorAutoScaler.Decision.AtCap.class, d);
        assertEquals(200, ((ParlorAutoScaler.Decision.AtCap) d).over());
    }

    @Test void atCap_exactlyMaxIsNotOver() {
        // 500 is the limit — a Parlor at exactly 500 is at capacity but not
        // overflowing. AtCap only fires when NEW arrival would push past.
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.FIREHOSE, 500, T0, T0);
        assertFalse(d instanceof ParlorAutoScaler.Decision.AtCap);
    }

    // ── invalid args ──────────────────────────────────────────────────

    @Test void nullCurrent_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> ParlorAutoScaler.decide(null, 5, T0, T0));
    }

    @Test void negativeOccupancy_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> ParlorAutoScaler.decide(ParlorPresenceMode.FULL, -1, T0, T0));
    }

    // ── narration content ─────────────────────────────────────────────

    @Test void narrationUp_mentionsCrowd_forFirehose() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED_STRICT, 101, T0, T0);
        var t = (ParlorAutoScaler.Decision.Transition) d;
        assertTrue(t.narration().toLowerCase().contains("crowd"),
            "firehose narration should reference crowd: " + t.narration());
    }

    @Test void narrationDown_mentionsQuietOrReturn_forFull() {
        var d = ParlorAutoScaler.decide(ParlorPresenceMode.SAMPLED, 3,
            T0, T0.plus(Duration.ofSeconds(61)));
        var t = (ParlorAutoScaler.Decision.Transition) d;
        var msg = t.narration().toLowerCase();
        assertTrue(msg.contains("quiet") || msg.contains("return"),
            "back-to-FULL narration should read as quieting: " + t.narration());
    }
}

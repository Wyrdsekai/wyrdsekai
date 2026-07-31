package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOAK-ONLY guard ({@link SoakTimeScale}): the time-compression knob must be a
 * pure no-op in production (absent / unparseable / &lt;1 → factor 1.0, inactive,
 * identity compress) and scale linearly when a soak sets it. Mirrors the
 * soak-only gating: zero behavior change by default.
 */
class SoakTimeScaleTest {

    @AfterEach
    void clearProp() {
        System.clearProperty("wyrd.soak.time.scale");
    }

    @Test
    void absent_isRealTime_noOp() {
        System.clearProperty("wyrd.soak.time.scale");
        assertEquals(1.0, SoakTimeScale.factor(), 1e-9);
        assertFalse(SoakTimeScale.active(), "production default must be inactive");
        var d = Duration.ofMinutes(7);
        assertEquals(d, SoakTimeScale.compress(d), "factor 1.0 → identity compress");
    }

    @Test
    void belowOne_clampsToRealTime() {
        System.setProperty("wyrd.soak.time.scale", "0.25");
        assertEquals(1.0, SoakTimeScale.factor(), 1e-9, "sub-1 must not slow time below real");
        assertFalse(SoakTimeScale.active());
    }

    @Test
    void unparseable_fallsBackToRealTime() {
        System.setProperty("wyrd.soak.time.scale", "fast");
        assertEquals(1.0, SoakTimeScale.factor(), 1e-9);
        assertFalse(SoakTimeScale.active());
    }

    @Test
    void scaleActive_compressesLinearly() {
        System.setProperty("wyrd.soak.time.scale", "288");
        assertEquals(288.0, SoakTimeScale.factor(), 1e-9);
        assertTrue(SoakTimeScale.active());
        // compress() scales elapsed UP: an interval of real time reads as factor× sim-time,
        // so a wall-clock gate trips after gate/factor of REAL time.
        var realTick = Duration.ofSeconds(25);
        var sim = SoakTimeScale.compress(realTick);
        assertEquals(realTick.toNanos() * 288L, sim.toNanos(),
            "compress must scale linearly by the factor");
        // 25 real seconds reads as 2h of sim-elapsed → the stagnation 2h gate trips.
        assertTrue(sim.compareTo(Duration.ofHours(2)) >= 0,
            "25 real s × 288 ≥ 2h sim → the 2h stagnation gate fires after ~25 real s");
    }

    @Test
    void compress_handlesNullAndNegative() {
        System.setProperty("wyrd.soak.time.scale", "288");
        // negative (clock skew) and null pass through unscaled rather than exploding.
        var neg = Duration.ofSeconds(-5);
        assertEquals(neg, SoakTimeScale.compress(neg));
    }
}

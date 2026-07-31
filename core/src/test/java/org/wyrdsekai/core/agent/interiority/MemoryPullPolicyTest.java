package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPullPolicyTest {

    @Test void high_energy_pulls_more_than_low_energy_on_average() {
        int hi = avgN(0.9, 0.9, "none", Map.of(), false);
        int lo = avgN(0.1, 0.1, "none", Map.of(), false);
        assertThat(hi).isGreaterThan(lo);
    }

    @Test void reflective_prior_pulls_more_than_intense_prior() {
        int reflect = avgN(0.6, 0.6, "reflect", Map.of(), false);
        int intense = avgN(0.6, 0.6, "intense", Map.of(), false);
        assertThat(reflect).isGreaterThan(intense);
    }

    @Test void high_curiosity_pulls_more_than_high_frustration() {
        int curious     = avgN(0.6, 0.6, "none", Map.of("Curiosity", 0.95), false);
        int frustrated  = avgN(0.6, 0.6, "none", Map.of("Frustration", 0.95), false);
        assertThat(curious).isGreaterThan(frustrated);
    }

    @Test void pre_sleep_pulls_more_than_awake() {
        int awake = avgN(0.4, 0.4, "none", Map.of(), false);
        int dream = avgN(0.4, 0.4, "none", Map.of(), true);
        assertThat(dream).isGreaterThan(awake);
    }

    @Test void output_is_clamped_to_zero_through_max() {
        for (int i = 0; i < 50; i++) {
            int n = MemoryPullPolicy.decideN(0.5, 0.5, null, null, false);
            assertThat(n).isBetween(0, MemoryPullPolicy.MAX_PULLS);
        }
    }

    @Test void zeroes_everywhere_can_yield_zero_pulls_sometimes() {
        // Lowest possible inputs — base ~1, jitter ±0.4 → some calls land at 0.
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 200; i++) {
            seen.add(MemoryPullPolicy.decideN(0, 0, "intense", Map.of("Frustration", 0.95), false));
        }
        // The point: the policy can sometimes produce 0 — not always at least 1.
        assertThat(seen).contains(0);
    }

    @Test void null_drive_map_is_tolerated() {
        int n = MemoryPullPolicy.decideN(0.5, 0.5, "reflect", null, false);
        assertThat(n).isBetween(0, MemoryPullPolicy.MAX_PULLS);
    }

    @Test void unknown_prior_label_falls_through_to_neutral() {
        // Should not throw and should produce a sensible value.
        int n = MemoryPullPolicy.decideN(0.5, 0.5, "made_up_label", Map.of(), false);
        assertThat(n).isBetween(0, MemoryPullPolicy.MAX_PULLS);
    }

    /** Average of N runs to smooth out the jitter. */
    private static int avgN(double e, double c, String prior, Map<String, Double> drives, boolean preSleep) {
        int sum = 0;
        int iters = 30;
        for (int i = 0; i < iters; i++) {
            sum += MemoryPullPolicy.decideN(e, c, prior, drives, preSleep);
        }
        return sum / iters;
    }
}

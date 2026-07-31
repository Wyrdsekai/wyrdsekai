package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * drive-modulated cadence.
 *
 * <p>These tests are pure-functional and run in microseconds. They pin the
 * direction of each modulator (louder drives → sooner; lower energy → later;
 * unresolved wants → sooner). They deliberately don't pin exact magnitudes —
 * those are tunable constants — only the sign.
 */
class CadenceModulatorTest {

    private static final Duration BASE = Duration.ofMinutes(30);

    @Test void baseline_returns_close_to_base_interval() {
        var delay = CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 1.0, 0, 0.0);
        // No drives, full energy, no wants, no jitter → very close to base.
        assertThat(delay).isBetween(
            Duration.ofMinutes(28), Duration.ofMinutes(32));
    }

    @Test void loud_drive_shortens_delay() {
        var calm = CadenceModulator.nextDelay(BASE, Map.of("Curiosity", 0.2), 0.7, 1.0, 0, 0.0);
        var loud = CadenceModulator.nextDelay(BASE, Map.of("Curiosity", 0.95), 0.7, 1.0, 0, 0.0);
        assertThat(loud).isLessThan(calm);
    }

    @Test void low_energy_lengthens_delay() {
        var rested  = CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 1.0, 0, 0.0);
        var tired   = CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 0.1, 0, 0.0);
        assertThat(tired).isGreaterThan(rested);
    }

    @Test void unresolved_wants_shorten_delay() {
        var none = CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 1.0, 0, 0.0);
        var some = CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 1.0, 3, 0.0);
        assertThat(some).isLessThan(none);
    }

    @Test void jitter_perturbs_consecutive_calls() {
        var seen = new HashSet<Long>();
        for (int i = 0; i < 8; i++) {
            seen.add(CadenceModulator.nextDelay(BASE, Map.of(), 0.7, 1.0, 0, 0.20).toMillis());
        }
        // 8 calls with 20% jitter should produce at least 4 distinct values.
        assertThat(seen.size()).isGreaterThanOrEqualTo(4);
    }

    @Test void delay_never_drops_below_5_seconds() {
        // Worst-case multiplier: huge drive over threshold + lots of wants + low base.
        var d = CadenceModulator.nextDelay(Duration.ofSeconds(10),
            Map.of("Frustration", 0.99), 0.5, 1.0, 4, 0.20);
        assertThat(d.toMillis()).isGreaterThanOrEqualTo(5_000);
    }

    @Test void pregate_skips_quiet_tick() {
        // No drives over threshold, no live wants, no state change, just woke.
        var run = CadenceModulator.shouldRunFullPass(
            Map.of("Calm", 0.5), 0.7, 0, false, 10);
        assertThat(run).isFalse();
    }

    @Test void pregate_runs_when_drive_over_threshold() {
        var run = CadenceModulator.shouldRunFullPass(
            Map.of("Curiosity", 0.85), 0.7, 0, false, 10);
        assertThat(run).isTrue();
    }

    @Test void pregate_runs_when_bondholder_state_changed() {
        var run = CadenceModulator.shouldRunFullPass(Map.of(), 0.7, 0, true, 1);
        assertThat(run).isTrue();
    }

    @Test void pregate_runs_when_live_wants_and_some_time_passed() {
        var run = CadenceModulator.shouldRunFullPass(Map.of(), 0.7, 2, false, 6);
        assertThat(run).isTrue();
    }

    @Test void pregate_runs_after_long_quiet_stretch() {
        // 4-hour gap — give the agent a chance even if nothing screams.
        var run = CadenceModulator.shouldRunFullPass(Map.of(), 0.7, 0, false, 240);
        assertThat(run).isTrue();
    }

    @Test void null_base_falls_back_to_30min() {
        var delay = CadenceModulator.nextDelay(null, Map.of(), 0.7, 1.0, 0, 0.0);
        assertThat(delay).isBetween(Duration.ofMinutes(28), Duration.ofMinutes(32));
    }
}

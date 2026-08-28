package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paused recipe must be able to try again.
 *
 * <p>Three consecutive deploy failures trip the welfare ceiling, which exists so a recipe
 * cannot grind a companion down by failing at her repeatedly. That is right. The resting
 * state was not: paused meant paused until a person noticed, which makes silence the
 * default outcome of a self-improvement loop on an unattended household node. Live
 * 2026-08-18, a configuration error held a recipe paused for days and nothing said so.
 */
class RecipeCircuitBreakerTest {

    private static final int LIMIT = 3;
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static RecipeCircuitBreaker.State at(int failures, Duration sinceLast) {
        return RecipeCircuitBreaker.stateFor(
            failures, LIMIT, sinceLast == null ? null : NOW.minus(sinceLast), NOW);
    }

    @Test
    void below_the_ceiling_nothing_is_gated() {
        assertThat(at(0, Duration.ofMinutes(1))).isEqualTo(RecipeCircuitBreaker.State.CLOSED);
        assertThat(at(2, Duration.ofMinutes(1))).isEqualTo(RecipeCircuitBreaker.State.CLOSED);
    }

    @Test
    void tripping_the_ceiling_stops_it_immediately() {
        assertThat(at(3, Duration.ofMinutes(5))).isEqualTo(RecipeCircuitBreaker.State.OPEN);
    }

    @Test
    void after_a_day_exactly_one_attempt_is_allowed() {
        assertThat(at(3, Duration.ofHours(23)))
            .as("still cooling down")
            .isEqualTo(RecipeCircuitBreaker.State.OPEN);
        assertThat(at(3, Duration.ofHours(25)))
            .as("cooled down — try once")
            .isEqualTo(RecipeCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void each_further_failure_doubles_the_wait() {
        assertThat(RecipeCircuitBreaker.cooldownFor(3, LIMIT)).isEqualTo(Duration.ofHours(24));
        assertThat(RecipeCircuitBreaker.cooldownFor(4, LIMIT)).isEqualTo(Duration.ofHours(48));
        assertThat(RecipeCircuitBreaker.cooldownFor(5, LIMIT)).isEqualTo(Duration.ofHours(96));
    }

    @Test
    void it_never_backs_off_so_far_that_it_stops_checking() {
        // However bad it gets, come back within a week — a permanent stop is the thing
        // this exists to prevent.
        assertThat(RecipeCircuitBreaker.cooldownFor(50, LIMIT))
            .isEqualTo(RecipeCircuitBreaker.MAX_COOLDOWN);
        assertThat(at(50, Duration.ofDays(8)))
            .isEqualTo(RecipeCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void a_success_closes_it_by_arithmetic_not_bookkeeping() {
        // consecutiveDeployFailures is queue-derived: a SUCCEEDED row breaks the streak,
        // so the breaker closes with no state to reset and nothing to get out of sync.
        assertThat(at(0, Duration.ofMinutes(1))).isEqualTo(RecipeCircuitBreaker.State.CLOSED);
    }

    @Test
    void at_worst_it_costs_one_run_per_day() {
        // The welfare question: can this grind her? Walk a year of ticks against a
        // permanently broken recipe and count the attempts it would allow.
        int attempts = 0;
        var lastFailure = NOW;
        int failures = LIMIT;
        for (var t = NOW; t.isBefore(NOW.plus(Duration.ofDays(365)));
                t = t.plus(Duration.ofHours(1))) {
            if (RecipeCircuitBreaker.stateFor(failures, LIMIT, lastFailure, t)
                    == RecipeCircuitBreaker.State.HALF_OPEN) {
                attempts++;
                failures++;             // the probe failed too
                lastFailure = t;
            }
        }
        // Steady state is the 7-day cap, so a permanently broken recipe costs roughly one
        // attempt a week — about 52 a year, plus a few during the doubling ramp. Compare
        // with the hourly poll it would otherwise ride: ~8,700.
        assertThat(attempts)
            .as("a year of a permanently broken recipe costs weekly attempts, not hourly")
            .isBetween(40, 60);
        assertThat(Duration.ofDays(365).dividedBy(Math.max(1, attempts)))
            .as("average gap between attempts is on the order of the weekly cap")
            .isGreaterThanOrEqualTo(Duration.ofDays(5));
    }

    @Test
    void an_unknown_last_run_lets_one_through_rather_than_latching_shut() {
        // The safe direction differs from the decay case: being wrong here costs one
        // extra run, while the other default costs a loop that never runs again and
        // never says why.
        assertThat(at(5, null)).isEqualTo(RecipeCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void a_clock_that_goes_backwards_does_not_open_the_gate() {
        assertThat(RecipeCircuitBreaker.stateFor(5, LIMIT, NOW.plusSeconds(600), NOW))
            .isEqualTo(RecipeCircuitBreaker.State.OPEN);
    }
}

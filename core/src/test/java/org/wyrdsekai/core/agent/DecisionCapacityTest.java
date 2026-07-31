package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for DecisionCapacity (Phase H: Decision Capacity).
 */
class DecisionCapacityTest {

    @Test void default_low_for_unknown_domain() {
        var dc = DecisionCapacity.newAgent();

        assertThat(dc.getCapacity("codeplane")).isCloseTo(0.1, within(0.001));
        assertThat(dc.getCapacity("household")).isCloseTo(0.1, within(0.001));
    }

    @Test void success_increases_capacity() {
        var dc = DecisionCapacity.newAgent();

        dc.recordSuccess("codeplane");

        assertThat(dc.getCapacity("codeplane"))
            .isCloseTo(0.1 + DecisionCapacity.SUCCESS_INCREMENT, within(0.001));
    }

    @Test void failure_decreases_capacity() {
        var dc = new DecisionCapacity(Map.of("codeplane", 0.5));

        dc.recordFailure("codeplane");

        assertThat(dc.getCapacity("codeplane"))
            .isCloseTo(0.5 - DecisionCapacity.FAILURE_DECREMENT, within(0.001));
    }

    @Test void capacity_capped_at_one() {
        var dc = new DecisionCapacity(Map.of("codeplane", 0.98));

        dc.recordSuccess("codeplane");

        assertThat(dc.getCapacity("codeplane")).isCloseTo(1.0, within(0.001));
    }

    @Test void capacity_floored_at_zero() {
        var dc = new DecisionCapacity(Map.of("codeplane", 0.02));

        dc.recordFailure("codeplane");

        assertThat(dc.getCapacity("codeplane")).isCloseTo(0.0, within(0.001));
    }

    @Test void decay_over_time() {
        var dc = new DecisionCapacity(Map.of("codeplane", 0.8));

        dc.decay("codeplane", Duration.ofDays(10));

        assertThat(dc.getCapacity("codeplane"))
            .isCloseTo(0.8 - (DecisionCapacity.DECAY_PER_DAY * 10), within(0.001));
    }

    @Test void decay_does_not_go_below_zero() {
        var dc = new DecisionCapacity(Map.of("codeplane", 0.05));

        dc.decay("codeplane", Duration.ofDays(365));

        assertThat(dc.getCapacity("codeplane")).isCloseTo(0.0, within(0.001));
    }

    @Test void domain_independence() {
        var dc = DecisionCapacity.newAgent();

        dc.recordSuccess("codeplane");
        dc.recordSuccess("codeplane");
        dc.recordSuccess("codeplane");

        assertThat(dc.getCapacity("codeplane"))
            .isCloseTo(0.1 + 3 * DecisionCapacity.SUCCESS_INCREMENT, within(0.001));
        assertThat(dc.getCapacity("iot")).isCloseTo(0.1, within(0.001));
    }

    @Test void experienced_factory_has_varied_scores() {
        var dc = DecisionCapacity.experienced();

        assertThat(dc.getCapacity("household_management")).isCloseTo(0.8, within(0.001));
        assertThat(dc.getCapacity("social_interaction")).isCloseTo(0.7, within(0.001));
        assertThat(dc.getCapacity("monitoring")).isCloseTo(0.6, within(0.001));
        assertThat(dc.getCapacity("codeplane_operations")).isCloseTo(0.4, within(0.001));
        // Unknown domain still low
        assertThat(dc.getCapacity("unknown")).isCloseTo(0.1, within(0.001));
    }

    @Test void prompt_context_includes_domains() {
        var dc = new DecisionCapacity(Map.of(
            "household_management", 0.8,
            "security_assessment", 0.1
        ));

        var ctx = dc.buildPromptContext();

        assertThat(ctx).contains("Decision Capacity");
        assertThat(ctx).contains("household_management");
        assertThat(ctx).contains("high confidence");
        assertThat(ctx).contains("security_assessment");
        assertThat(ctx).contains("defer to others");
    }

    @Test void empty_capacity_returns_empty_prompt() {
        var dc = DecisionCapacity.newAgent();
        assertThat(dc.buildPromptContext()).isEmpty();
    }

    @Test void decay_unknown_domain_is_noop() {
        var dc = DecisionCapacity.newAgent();
        dc.decay("nonexistent", Duration.ofDays(100));
        // Should not throw or add the domain
        assertThat(dc.scores()).isEmpty();
    }
}

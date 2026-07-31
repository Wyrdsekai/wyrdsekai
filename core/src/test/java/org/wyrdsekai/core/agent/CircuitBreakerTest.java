package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test void initial_state_has_full_authority() {
        var cb = CircuitBreaker.initial();
        assertThat(cb.authorityLevel()).isEqualTo(1.0);
        assertThat(cb.enforcementCount()).isEqualTo(0);
        assertThat(cb.confirmationCount()).isEqualTo(0);
        assertThat(cb.isObservationOnly()).isFalse();
    }

    @Test void record_enforcement_increments_count() {
        var cb = CircuitBreaker.initial().recordEnforcement();
        assertThat(cb.enforcementCount()).isEqualTo(1);
        assertThat(cb.confirmationCount()).isEqualTo(0);
    }

    @Test void record_confirmation_increments_count() {
        var cb = CircuitBreaker.initial()
            .recordEnforcement()
            .recordConfirmation();
        assertThat(cb.enforcementCount()).isEqualTo(1);
        assertThat(cb.confirmationCount()).isEqualTo(1);
    }

    @Test void high_enforcement_low_confirmation_drops_authority() {
        // Simulate many enforcements with no confirmations
        var cb = CircuitBreaker.initial();
        for (int i = 0; i < 40; i++) {
            cb = cb.recordEnforcement();
        }
        // enforcement rate = 40/100 = 0.4 > 0.3 threshold
        // confirmation rate = 0/40 = 0.0 < 0.5 threshold
        cb = cb.adjustAuthority();
        assertThat(cb.authorityLevel()).isLessThan(1.0);
    }

    @Test void authority_below_threshold_triggers_observation_only() {
        var cb = new CircuitBreaker(50, 0, 100, 0.25);
        assertThat(cb.isObservationOnly()).isTrue();
    }

    @Test void authority_above_threshold_is_not_observation_only() {
        var cb = new CircuitBreaker(50, 0, 100, 0.5);
        assertThat(cb.isObservationOnly()).isFalse();
    }

    @Test void authority_recovers_when_enforcement_rate_normalizes() {
        // Low enforcement count — not enough data, slow recovery
        var cb = new CircuitBreaker(1, 1, 100, 0.5);
        cb = cb.adjustAuthority();
        assertThat(cb.authorityLevel()).isGreaterThan(0.5);
    }

    @Test void enforcement_rate_calculation() {
        var cb = new CircuitBreaker(25, 10, 100, 1.0);
        assertThat(cb.enforcementRate()).isEqualTo(0.25);
        assertThat(cb.confirmationRate()).isEqualTo(0.4);
    }

    @Test void describe_full_authority() {
        var cb = CircuitBreaker.initial();
        assertThat(cb.describe()).contains("clear");
    }

    @Test void describe_observation_only() {
        var cb = new CircuitBreaker(50, 0, 100, 0.2);
        assertThat(cb.describe()).contains("observing only");
    }

    @Test void window_sliding_halves_counts() {
        // Fill window beyond capacity
        var cb = new CircuitBreaker(100, 50, 100, 1.0);
        cb = cb.recordEnforcement();
        // Should have been halved: 101/2 = 50, 50/2 = 25
        assertThat(cb.enforcementCount()).isEqualTo(50);
        assertThat(cb.confirmationCount()).isEqualTo(25);
    }
}

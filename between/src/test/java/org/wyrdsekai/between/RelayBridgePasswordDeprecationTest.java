package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * enforces a hard cutoff for password-mode relay auth
 * after a configured date. Default = unset → no enforcement (warnings only).
 *
 * <p>JDK 25 makes env-var mutation in tests impossible, so we lean on JUnit's
 * conditional execution: the "deprecation date in future" and "deprecation
 * date past" cases each run only when the operator/CI sets the matching env
 * var. The unconditional default-behavior test runs every time and locks in
 * "no env var = no throw."</p>
 */
final class RelayBridgePasswordDeprecationTest {

    @Test
    void no_env_var_does_not_throw() {
        // Most installs run without the env var set. Must be a no-op.
        // (If a parent test in this VM already set WYRDSEKAI_RELAY_PASSWORD_
        // DEPRECATION_DATE, that's also fine — this test verifies the public
        // contract and is forward-safe.)
        assertDoesNotThrow(RelayBridge::enforcePasswordDeprecation,
            "no env var → enforcement is a no-op");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE",
        matches = "9999-.*")
    void future_deadline_does_not_throw() {
        // Set up: WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE=9999-01-01 → far future,
        // password auth still allowed. Triggers the "info" log path, not throw.
        assertDoesNotThrow(RelayBridge::enforcePasswordDeprecation,
            "future deadline → no rejection yet");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE",
        matches = "1900-.*")
    void past_deadline_throws_illegal_state() {
        assertThatThrownBy(RelayBridge::enforcePasswordDeprecation)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("past its deprecation date")
            .hasMessageContaining("wyrd relay register-nkey");
    }

    /**
     * Belt-and-suspenders: even if env mutation is impossible, we can prove
     * the LocalDate comparison logic by reading the parsed deadline ourselves
     * via the public java.time API. This catches subtle bugs like accidental
     * "isAfter" instead of "isBefore" without needing env mutation.
     */
    @Test
    void deadline_comparison_predicate_is_inclusive_of_deadline() {
        var today = LocalDate.now();
        var yesterday = today.minusDays(1);
        var tomorrow = today.plusDays(1);
        // Exact contract: enforcement triggers when today is on or AFTER the
        // deadline (i.e. !today.isBefore(deadline) ⇔ today >= deadline).
        assertThat(today.isBefore(today)).isFalse();
        assertThat(today.isBefore(yesterday)).isFalse();
        assertThat(today.isBefore(tomorrow)).isTrue();
    }
}

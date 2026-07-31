package org.wyrdsekai.daemon.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ThermalStateTest {

    @Test
    void normalConditions_charging_coolTemp() {
        var state = ThermalState.evaluate(true, 100, 30f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.NORMAL);
        assertThat(state.acceptsRequests()).isTrue();
    }

    @Test
    void warmTemp_reducesThreads() {
        var state = ThermalState.evaluate(true, 100, 39f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.WARM);
        assertThat(state.recommendedThreads(4)).isEqualTo(2);
        assertThat(state.acceptsRequests()).isTrue();
    }

    @Test
    void hotTemp_singleThread() {
        var state = ThermalState.evaluate(true, 100, 42f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.HOT);
        assertThat(state.recommendedThreads(4)).isEqualTo(1);
        assertThat(state.effectiveSlots(4)).isEqualTo(1);
    }

    @Test
    void criticalTemp_stopsInference() {
        var state = ThermalState.evaluate(true, 100, 46f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.CRITICAL);
        assertThat(state.recommendedThreads(4)).isZero();
        assertThat(state.effectiveSlots(4)).isZero();
        assertThat(state.acceptsRequests()).isFalse();
    }

    @Test
    void lowBattery_notCharging_critical() {
        var state = ThermalState.evaluate(false, 15, 30f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.CRITICAL);
    }

    @Test
    void mediumBattery_notCharging_hot() {
        var state = ThermalState.evaluate(false, 40, 30f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.HOT);
    }

    @Test
    void fullBattery_notCharging_normal() {
        var state = ThermalState.evaluate(false, 80, 30f);
        assertThat(state.level()).isEqualTo(ThermalState.ThrottleLevel.NORMAL);
    }

    @ParameterizedTest
    @CsvSource({
        "true,  100, 30, NORMAL",
        "true,  100, 39, WARM",
        "true,  100, 41, HOT",
        "true,  100, 46, CRITICAL",
        "false,  15, 30, CRITICAL",
        "false,  40, 30, HOT",
        "false,  80, 30, NORMAL",
        // Temperature takes priority over battery
        "false,  80, 46, CRITICAL",
        "false,  15, 46, CRITICAL",
    })
    void evaluate_allCombinations(boolean charging, int battery, float temp,
                                   ThermalState.ThrottleLevel expected) {
        var state = ThermalState.evaluate(charging, battery, temp);
        assertThat(state.level()).isEqualTo(expected);
    }

    @Test
    void effectiveSlots_normalPreservesAll() {
        var state = ThermalState.evaluate(true, 100, 30f);
        assertThat(state.effectiveSlots(4)).isEqualTo(4);
    }

    @Test
    void effectiveSlots_hotCapsAtOne() {
        var state = ThermalState.evaluate(true, 100, 42f);
        assertThat(state.effectiveSlots(4)).isEqualTo(1);
        assertThat(state.effectiveSlots(1)).isEqualTo(1);
    }
}

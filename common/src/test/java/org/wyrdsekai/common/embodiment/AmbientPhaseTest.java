package org.wyrdsekai.common.embodiment;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AmbientPhase} — the Layer 5 phase mapper.
 */
class AmbientPhaseTest {

    @Test
    void hourBoundariesMapToCorrectPhase() {
        // DAWN: 05:00-10:59
        assertThat(AmbientPhase.fromHour(5)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.fromHour(7)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.fromHour(10)).isEqualTo(AmbientPhase.DAWN);

        // MIDDAY: 11:00-16:59
        assertThat(AmbientPhase.fromHour(11)).isEqualTo(AmbientPhase.MIDDAY);
        assertThat(AmbientPhase.fromHour(13)).isEqualTo(AmbientPhase.MIDDAY);
        assertThat(AmbientPhase.fromHour(16)).isEqualTo(AmbientPhase.MIDDAY);

        // DUSK: 17:00-20:59
        assertThat(AmbientPhase.fromHour(17)).isEqualTo(AmbientPhase.DUSK);
        assertThat(AmbientPhase.fromHour(20)).isEqualTo(AmbientPhase.DUSK);

        // NIGHT: 21:00-04:59 (wraps midnight)
        assertThat(AmbientPhase.fromHour(21)).isEqualTo(AmbientPhase.NIGHT);
        assertThat(AmbientPhase.fromHour(23)).isEqualTo(AmbientPhase.NIGHT);
        assertThat(AmbientPhase.fromHour(0)).isEqualTo(AmbientPhase.NIGHT);
        assertThat(AmbientPhase.fromHour(4)).isEqualTo(AmbientPhase.NIGHT);
    }

    @Test
    void fromInstantUsesGivenZone() {
        // 2026-05-24 13:00 UTC → MIDDAY in UTC, but NIGHT in UTC-13 (overflow case)
        // Stick with normal zones to keep the test deterministic.
        var noonUtc = LocalDateTime.of(LocalDate.of(2026, 5, 24), java.time.LocalTime.of(13, 0))
            .atZone(ZoneId.of("UTC")).toInstant();
        assertThat(AmbientPhase.fromInstant(noonUtc, ZoneId.of("UTC")))
            .isEqualTo(AmbientPhase.MIDDAY);

        var dawn = LocalDateTime.of(LocalDate.of(2026, 5, 24), java.time.LocalTime.of(6, 30))
            .atZone(ZoneId.of("UTC")).toInstant();
        assertThat(AmbientPhase.fromInstant(dawn, ZoneId.of("UTC")))
            .isEqualTo(AmbientPhase.DAWN);

        var nightLate = LocalDateTime.of(LocalDate.of(2026, 5, 24), java.time.LocalTime.of(23, 30))
            .atZone(ZoneId.of("UTC")).toInstant();
        assertThat(AmbientPhase.fromInstant(nightLate, ZoneId.of("UTC")))
            .isEqualTo(AmbientPhase.NIGHT);
    }

    @Test
    void syntheticPhaseSweepsAllFourPhases() {
        // 4-second synthetic day: 1 second per phase.
        assertThat(AmbientPhase.syntheticPhase(0, 4)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.syntheticPhase(1, 4)).isEqualTo(AmbientPhase.MIDDAY);
        assertThat(AmbientPhase.syntheticPhase(2, 4)).isEqualTo(AmbientPhase.DUSK);
        assertThat(AmbientPhase.syntheticPhase(3, 4)).isEqualTo(AmbientPhase.NIGHT);
        // wrap to next day
        assertThat(AmbientPhase.syntheticPhase(4, 4)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.syntheticPhase(7, 4)).isEqualTo(AmbientPhase.NIGHT);
    }

    @Test
    void syntheticPhaseHandlesLargerWindow() {
        // 8-second day: 2 seconds per phase.
        assertThat(AmbientPhase.syntheticPhase(0, 8)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.syntheticPhase(1, 8)).isEqualTo(AmbientPhase.DAWN);
        assertThat(AmbientPhase.syntheticPhase(2, 8)).isEqualTo(AmbientPhase.MIDDAY);
        assertThat(AmbientPhase.syntheticPhase(5, 8)).isEqualTo(AmbientPhase.DUSK);
        assertThat(AmbientPhase.syntheticPhase(7, 8)).isEqualTo(AmbientPhase.NIGHT);
    }

    @Test
    void syntheticPhaseRejectsZeroOrNegativeDay() {
        assertThatThrownBy(() -> AmbientPhase.syntheticPhase(0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmbientPhase.syntheticPhase(0, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysRoundTrip() {
        for (var phase : AmbientPhase.values()) {
            assertThat(AmbientPhase.ofKey(phase.key())).isEqualTo(phase);
        }
        assertThat(AmbientPhase.ofKey(null)).isNull();
        assertThat(AmbientPhase.ofKey("notaphase")).isNull();
    }

    @Test
    void canonicalLocalTimesFallInOwnPhase() {
        for (var phase : AmbientPhase.values()) {
            var hour = phase.canonicalLocalTime().getHour();
            assertThat(AmbientPhase.fromHour(hour))
                .as("canonical hour of %s must round-trip to itself", phase)
                .isEqualTo(phase);
        }
    }

    @Test
    void fromInstantWithoutZoneUsesSystemDefault() {
        // Sanity check: doesn't throw and returns a valid phase.
        var phase = AmbientPhase.fromInstant(Instant.now());
        assertThat(phase).isIn((Object[]) AmbientPhase.values());
    }
}

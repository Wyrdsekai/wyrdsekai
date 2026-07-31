package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;
import static org.wyrdsekai.core.agent.DriveConfig.*;

class DriveConfigTest {

    // ── Hill Function ────────────────────────────────────────────────────

    @Test
    void hillZeroInputReturnsZero() {
        assertThat(hill(0.0, 2.0, 0.5)).isEqualTo(0.0);
    }

    @Test
    void hillAtKReturnsHalf() {
        // H(K, n, K) should always be 0.5 regardless of n
        assertThat(hill(0.5, 1.0, 0.5)).isCloseTo(0.5, within(0.001));
        assertThat(hill(0.5, 2.0, 0.5)).isCloseTo(0.5, within(0.001));
        assertThat(hill(0.5, 3.0, 0.5)).isCloseTo(0.5, within(0.001));
    }

    @Test
    void hillN1IsMichaelisMenten() {
        // n=1: H(x) = x / (K + x), the Michaelis-Menten form
        double result = hill(0.8, 1.0, 0.4);
        double expected = 0.8 / (0.4 + 0.8);
        assertThat(result).isCloseTo(expected, within(0.001));
    }

    @Test
    void hillHigherNIsSteeper() {
        // At x=0.3 (below K=0.5), higher n should give LOWER response (more switch-like)
        double h1 = hill(0.3, 1.0, 0.5);
        double h2 = hill(0.3, 2.0, 0.5);
        double h3 = hill(0.3, 3.0, 0.5);
        assertThat(h1).isGreaterThan(h2);
        assertThat(h2).isGreaterThan(h3);

        // At x=0.8 (above K=0.5), higher n should give HIGHER response
        double h1h = hill(0.8, 1.0, 0.5);
        double h2h = hill(0.8, 2.0, 0.5);
        double h3h = hill(0.8, 3.0, 0.5);
        assertThat(h3h).isGreaterThan(h2h);
        assertThat(h2h).isGreaterThan(h1h);
    }

    @Test
    void hillApproachesOneForLargeInput() {
        assertThat(hill(10.0, 2.0, 0.5)).isCloseTo(1.0, within(0.01));
    }

    @ParameterizedTest
    @CsvSource({
        "0.1, 1.0, 0.5, 0.167",   // Low input, linear
        "0.5, 2.0, 0.5, 0.500",   // At K, any n
        "0.9, 3.0, 0.5, 0.854",   // High input, switch-like
    })
    void hillParameterizedCases(double x, double n, double K, double expected) {
        assertThat(hill(x, n, K)).isCloseTo(expected, within(0.01));
    }

    // ── Michaelis-Menten ─────────────────────────────────────────────────

    @Test
    void michaelisMentenZeroDeficitReturnsZero() {
        assertThat(michaelisMenten(0.0, 1.0, 0.3)).isEqualTo(0.0);
    }

    @Test
    void michaelisMentenSaturatesAtVmax() {
        // Large deficit → approaches Vmax
        assertThat(michaelisMenten(100.0, 1.0, 0.3)).isCloseTo(1.0, within(0.01));
    }

    @Test
    void michaelisMentenAtKmIsHalfVmax() {
        assertThat(michaelisMenten(0.3, 1.0, 0.3)).isCloseTo(0.5, within(0.001));
    }

    // ── Drive Defaults ───────────────────────────────────────────────────

    @Test
    void defaultsHasEightDrives() {
        var configs = DriveConfig.defaults();
        assertThat(configs).hasSize(DRIVE_COUNT);
    }

    @Test
    void defaultCrossModIsEightElements() {
        for (var cfg : DriveConfig.defaults()) {
            assertThat(cfg.crossMod()).hasSize(DRIVE_COUNT);
        }
    }

    @Test
    void seekingIsGradual() {
        var seeking = DriveConfig.defaults()[SEEKING];
        assertThat(seeking.hillN()).isLessThanOrEqualTo(2.0);
        assertThat(seeking.baseRate()).isGreaterThan(0);
    }

    @Test
    void vigilanceIsSwitchLike() {
        var vigilance = DriveConfig.defaults()[VIGILANCE];
        assertThat(vigilance.hillN()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void griefIsEventOnly() {
        var grief = DriveConfig.defaults()[GRIEF];
        assertThat(grief.baseRate()).isEqualTo(0.0);
    }

    @Test
    void frustrationIsEventOnly() {
        var frust = DriveConfig.defaults()[FRUSTRATION];
        assertThat(frust.baseRate()).isEqualTo(0.0);
    }

    // ── Cross-Modulation Invariants ──────────────────────────────────────

    @Test
    void playAndVigilanceSuppressEachOther() {
        var configs = DriveConfig.defaults();
        // PLAY's effect on VIGILANCE (play row, vigilance column)
        assertThat(configs[PLAY].crossMod()[VIGILANCE]).isLessThan(0);
        // VIGILANCE's effect on PLAY
        assertThat(configs[VIGILANCE].crossMod()[PLAY]).isLessThan(0);
    }

    @Test
    void griefSuppressesPlayAndSeeking() {
        var configs = DriveConfig.defaults();
        assertThat(configs[GRIEF].crossMod()[PLAY]).isLessThan(0);
        assertThat(configs[GRIEF].crossMod()[SEEKING]).isLessThan(0);
    }

    @Test
    void affiliationAmplifiesPlay() {
        var configs = DriveConfig.defaults();
        assertThat(configs[AFFILIATION].crossMod()[PLAY]).isGreaterThan(0);
    }

    @Test
    void seekingAmplifiesCreativity() {
        var configs = DriveConfig.defaults();
        assertThat(configs[SEEKING].crossMod()[CREATIVITY]).isGreaterThan(0);
    }

    // ── Index Resolution ─────────────────────────────────────────────────

    @Test
    void indexForNewNames() {
        assertThat(indexFor("seeking")).isEqualTo(SEEKING);
        assertThat(indexFor("care")).isEqualTo(CARE);
        assertThat(indexFor("play")).isEqualTo(PLAY);
        assertThat(indexFor("vigilance")).isEqualTo(VIGILANCE);
        assertThat(indexFor("affiliation")).isEqualTo(AFFILIATION);
        assertThat(indexFor("grief")).isEqualTo(GRIEF);
        assertThat(indexFor("frustration")).isEqualTo(FRUSTRATION);
        assertThat(indexFor("creativity")).isEqualTo(CREATIVITY);
    }

    @Test
    void indexForLegacyNames() {
        assertThat(indexFor("curiosity")).isEqualTo(SEEKING);
        assertThat(indexFor("social")).isEqualTo(AFFILIATION);
        assertThat(indexFor("alertness")).isEqualTo(VIGILANCE);
    }

    @Test
    void indexForUnknownReturnsNegative() {
        assertThat(indexFor("nonexistent")).isEqualTo(-1);
    }
}

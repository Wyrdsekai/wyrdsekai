package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ComputeUnitNormalizerTest {

    private ComputeUnitNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ComputeUnitNormalizer();
    }

    @Test void default_tier_count() {
        assertThat(normalizer.tierCount()).isEqualTo(5);
    }

    @Test void phone_tier_cheapest() {
        assertThat(normalizer.getRate("phone")).isLessThan(normalizer.getRate("desktop"));
    }

    @Test void toCU_calculation() {
        // 1000 tokens on phone tier: 0.1 CU per 1K tokens = 0.1 CU
        assertThat(normalizer.toCU("phone", 1000)).isCloseTo(0.1, offset(0.001));
        // 2000 tokens on desktop: 2.0 CU per 1K = 4.0 CU
        assertThat(normalizer.toCU("desktop", 2000)).isCloseTo(4.0, offset(0.001));
    }

    @Test void custom_rate() {
        normalizer.setRate("custom", 3.0);
        assertThat(normalizer.getRate("custom")).isEqualTo(3.0);
        assertThat(normalizer.toCU("custom", 1000)).isCloseTo(3.0, offset(0.001));
    }

    @Test void unknown_tier_defaults_to_1() {
        assertThat(normalizer.getRate("unknown")).isEqualTo(1.0);
    }

    @Test void shapley_share_equal() {
        var shares = normalizer.shapleyShare(10.0,
            Map.of("node-1", 0.5, "node-2", 0.5));
        assertThat(shares.get("node-1")).isCloseTo(5.0, offset(0.001));
        assertThat(shares.get("node-2")).isCloseTo(5.0, offset(0.001));
    }

    @Test void shapley_share_proportional() {
        var shares = normalizer.shapleyShare(12.0,
            Map.of("node-1", 0.75, "node-2", 0.25));
        assertThat(shares.get("node-1")).isCloseTo(9.0, offset(0.001));
        assertThat(shares.get("node-2")).isCloseTo(3.0, offset(0.001));
    }

    @Test void shapley_share_empty() {
        var shares = normalizer.shapleyShare(10.0, Map.of());
        assertThat(shares).isEmpty();
    }

    @Test void all_rates() {
        var rates = normalizer.allRates();
        assertThat(rates).containsKeys("phone", "edge", "desktop", "cluster", "external");
    }
}

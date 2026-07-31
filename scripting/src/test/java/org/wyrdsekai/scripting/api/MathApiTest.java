package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pure-math wrappers.
 *
 * <p>Math is Tier 1 across the board (implicit cap {@code math.*}); these
 * tests pin determinism + edge-case behaviour rather than gating.</p>
 */
class MathApiTest {

    private final ItemWorldApi.MathApi math = new ItemWorldApi.MathApi();

    @Test
    void sum_handles_empty_and_nulls() {
        assertThat(math.sum(null)).isZero();
        assertThat(math.sum(List.of())).isZero();
        assertThat(math.sum(List.of(1, 2, 3))).isEqualTo(6.0);
    }

    @Test
    void mean_and_median_round_trip_known_inputs() {
        assertThat(math.mean(List.of(2, 4, 6, 8))).isEqualTo(5.0);
        assertThat(math.median(List.of(1, 2, 3))).isEqualTo(2.0);
        assertThat(math.median(List.of(1, 2, 3, 4))).isEqualTo(2.5);
    }

    @Test
    void stddev_matches_sample_formula() {
        // sample stddev of {2,4,4,4,5,5,7,9} = 2.0 (per Wikipedia stddev example)
        assertThat(math.stddev(List.of(2, 4, 4, 4, 5, 5, 7, 9))).isEqualTo(2.138089935299395, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void min_max_clamp_lerp_basics() {
        assertThat(math.min(List.of(3, 1, 2))).isEqualTo(1.0);
        assertThat(math.max(List.of(3, 1, 2))).isEqualTo(3.0);
        assertThat(math.clamp(5.0, 0.0, 3.0)).isEqualTo(3.0);
        assertThat(math.clamp(-2.0, 0.0, 3.0)).isZero();
        assertThat(math.lerp(0.0, 10.0, 0.5)).isEqualTo(5.0);
    }

    @Test
    void rounding_and_unary_funcs() {
        assertThat(math.round(2.6)).isEqualTo(3.0);
        assertThat(math.floor(2.9)).isEqualTo(2.0);
        assertThat(math.ceil(2.1)).isEqualTo(3.0);
        assertThat(math.abs(-7.5)).isEqualTo(7.5);
    }

    @Test
    void pow_log_exp_sqrt_consistent() {
        assertThat(math.pow(2.0, 10.0)).isEqualTo(1024.0);
        assertThat(math.sqrt(81.0)).isEqualTo(9.0);
        assertThat(math.exp(math.log(5.0))).isEqualTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void trig_returns_finite_values() {
        assertThat(math.sin(0.0)).isZero();
        assertThat(math.cos(0.0)).isEqualTo(1.0);
        assertThat(math.tan(0.0)).isZero();
    }

    @Test
    void quantile_linear_interpolates() {
        assertThat(math.quantile(List.of(1, 2, 3, 4, 5), 0.5)).isEqualTo(3.0);
        assertThat(math.quantile(List.of(1, 2, 3, 4, 5), 0.0)).isEqualTo(1.0);
        assertThat(math.quantile(List.of(1, 2, 3, 4, 5), 1.0)).isEqualTo(5.0);
        // Out-of-range q clamps to [0,1]
        assertThat(math.quantile(List.of(1, 2, 3), 1.5)).isEqualTo(3.0);
    }

    @Test
    void empty_inputs_never_explode() {
        assertThat(math.mean(List.of())).isZero();
        assertThat(math.median(List.of())).isZero();
        assertThat(math.stddev(List.of(1))).isZero();  // n<2 → 0
        assertThat(math.min(List.of())).isZero();
        assertThat(math.max(List.of())).isZero();
        assertThat(math.quantile(List.of(), 0.5)).isZero();
    }
}

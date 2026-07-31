package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link InferenceCostEstimator}.
 */
class InferenceCostEstimatorTest {

    @Test void claude_sonnet_cost_calculation() {
        // 1000 input tokens at $3/M + 500 output tokens at $15/M
        var cost = InferenceCostEstimator.estimateCostUSD(
                "claude-3-5-sonnet-20241022", 1000, 500);

        // (1000 * 3.0 + 500 * 15.0) / 1_000_000 = (3000 + 7500) / 1_000_000 = 0.0105
        assertThat(cost).isCloseTo(0.0105, within(0.00001));
    }

    @Test void claude_opus_cost_calculation() {
        var cost = InferenceCostEstimator.estimateCostUSD(
                "claude-opus-4", 2000, 1000);

        // (2000 * 15.0 + 1000 * 75.0) / 1_000_000 = (30000 + 75000) / 1_000_000 = 0.105
        assertThat(cost).isCloseTo(0.105, within(0.0001));
    }

    @Test void gpt_4o_cost_calculation() {
        var cost = InferenceCostEstimator.estimateCostUSD(
                "gpt-4o-2024-08-06", 5000, 2000);

        // (5000 * 2.5 + 2000 * 10.0) / 1_000_000 = (12500 + 20000) / 1_000_000 = 0.0325
        assertThat(cost).isCloseTo(0.0325, within(0.0001));
    }

    @Test void gpt_4o_mini_cost_matches_mini_not_base() {
        // "gpt-4o-mini" should match the "gpt-4o-mini" entry (longer key), not "gpt-4o"
        var cost = InferenceCostEstimator.estimateCostUSD(
                "gpt-4o-mini-2024-07-18", 10000, 5000);

        // (10000 * 0.15 + 5000 * 0.60) / 1_000_000 = (1500 + 3000) / 1_000_000 = 0.0045
        assertThat(cost).isCloseTo(0.0045, within(0.0001));
    }

    @Test void deepseek_chat_cost_calculation() {
        var cost = InferenceCostEstimator.estimateCostUSD(
                "deepseek-chat", 10000, 5000);

        // (10000 * 0.27 + 5000 * 1.10) / 1_000_000 = (2700 + 5500) / 1_000_000 = 0.0082
        assertThat(cost).isCloseTo(0.0082, within(0.0001));
    }

    @Test void unknown_model_uses_default_pricing() {
        var cost = InferenceCostEstimator.estimateCostUSD(
                "some-unknown-model-7b", 1000, 500);

        // (1000 * 1.0 + 500 * 3.0) / 1_000_000 = (1000 + 1500) / 1_000_000 = 0.0025
        assertThat(cost).isCloseTo(0.0025, within(0.0001));
    }

    @Test void zero_tokens_returns_zero_cost() {
        var cost = InferenceCostEstimator.estimateCostUSD("claude-sonnet", 0, 0);
        assertThat(cost).isEqualTo(0.0);
    }

    @Test void null_model_uses_default_pricing() {
        var cost = InferenceCostEstimator.estimateCostUSD(null, 1000, 500);

        // Default: (1000 * 1.0 + 500 * 3.0) / 1_000_000 = 0.0025
        assertThat(cost).isCloseTo(0.0025, within(0.0001));
    }

    @Test void only_input_tokens() {
        var cost = InferenceCostEstimator.estimateCostUSD("claude-sonnet", 1000000, 0);

        // 1M * 3.0 / 1M = $3.00
        assertThat(cost).isCloseTo(3.0, within(0.01));
    }

    @Test void only_output_tokens() {
        var cost = InferenceCostEstimator.estimateCostUSD("claude-sonnet", 0, 1000000);

        // 1M * 15.0 / 1M = $15.00
        assertThat(cost).isCloseTo(15.0, within(0.01));
    }

    @Test void claude_haiku_cost_calculation() {
        var cost = InferenceCostEstimator.estimateCostUSD(
                "claude-3-haiku-20240307", 10000, 5000);

        // (10000 * 0.25 + 5000 * 1.25) / 1_000_000 = (2500 + 6250) / 1_000_000 = 0.00875
        assertThat(cost).isCloseTo(0.00875, within(0.0001));
    }
}

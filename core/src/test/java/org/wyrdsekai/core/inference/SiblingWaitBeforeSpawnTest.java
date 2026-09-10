package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A managed llama-server must not be spawned for weights a sibling is about to serve.
 *
 * <p>Live on the household node, every boot: the bundled docker drive on :8200 takes ~30 s
 * to load; the server's own inference config ran at +5 s, found nothing serving the model,
 * and started a CPU copy of the 9B on :11525 — eight 8K slots, an 8 GiB prompt-cache
 * default, and not one request ever routed to it. 5.6 GB of RAM and swap, all day
 * (2026-09-07). The fix: wait, bounded, when a sibling is expected; size the child for the
 * CPU when that is where it runs; and cap its cache like every other launcher does.
 */
class SiblingWaitBeforeSpawnTest {

    private static final String DRIVE = "http://127.0.0.1:8200";

    @Test
    @DisplayName("the wait returns the sibling's url as soon as it answers, without spending the budget")
    void returnsWhenTheSiblingComesUp() {
        var polls = new AtomicInteger();
        var clock = new AtomicLong(1_000_000);
        var slept = new ArrayList<Long>();
        var found = InferenceConfig.awaitServedAt(
            () -> polls.incrementAndGet() >= 3 ? DRIVE : null,
            120_000, clock::get, ms -> { slept.add(ms); clock.addAndGet(ms); });
        assertThat(found).isEqualTo(DRIVE);
        assertThat(polls.get()).isEqualTo(3);
        assertThat(slept).containsExactly(InferenceConfig.SIBLING_POLL_MS, InferenceConfig.SIBLING_POLL_MS);
    }

    @Test
    @DisplayName("nobody comes: the wait gives up at the budget and lets the caller spawn")
    void givesUpAtTheBudget() {
        var clock = new AtomicLong(0);
        var polls = new AtomicInteger();
        var found = InferenceConfig.awaitServedAt(
            () -> { polls.incrementAndGet(); return null; },
            5_000, clock::get, ms -> clock.addAndGet(ms));
        assertThat(found).isNull();
        // 0, 2000, 4000, 5000 → four polls, the last sleep trimmed to the remaining second
        assertThat(polls.get()).isEqualTo(4);
        assertThat(clock.get()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("a zero budget polls once and does not sleep")
    void zeroBudgetIsOnePoll() {
        var polls = new AtomicInteger();
        var found = InferenceConfig.awaitServedAt(
            () -> { polls.incrementAndGet(); return null; },
            0, () -> 42L, ms -> { throw new AssertionError("must not sleep"); });
        assertThat(found).isNull();
        assertThat(polls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the configured url naming a bundled port means a sibling is expected")
    void configuredUrlOnABundledPortExpectsASibling() {
        assertThat(InferenceConfig.siblingExpected("http://localhost:8200/")).isTrue();
        assertThat(InferenceConfig.siblingExpected(DRIVE)).isTrue();
        assertThat(InferenceConfig.siblingExpected("http://127.0.0.1:8201")).isTrue();
    }

    @Test
    @DisplayName("the managed child's command line carries the cache cap and its context")
    void commandCarriesTheCacheCap() {
        List<String> cmd = LlamaServerManager.command("llama-server", "/m/9b.gguf", 11525, 0, 1, 8192, 1024);
        assertThat(cmd).containsSequence("--model", "/m/9b.gguf")
            .containsSequence("--port", "11525")
            .containsSequence("--parallel", "1")
            .containsSequence("--ctx-size", "8192")
            .containsSequence("--cache-ram", "1024")
            .doesNotContain("--n-gpu-layers");
        List<String> gpu = LlamaServerManager.command("llama-server", "/m/9b.gguf", 11525, 99, 4, 32768, 0);
        assertThat(gpu).containsSequence("--n-gpu-layers", "99")
            .containsSequence("--parallel", "4")
            .containsSequence("--cache-ram", "0");
    }
}

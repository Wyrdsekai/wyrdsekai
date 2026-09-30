package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voice training can be stopped by a deep sleep's deadline, and its time limit is real
 * (2026-09-28: the output was read to the end before the timed wait began).
 */
class TrainingProcessesTest {

    @Test
    void aRunThatEndsOnItsOwnReportsItsExitAndItsOutput() throws Exception {
        var lines = new ArrayList<String>();
        var out = TrainingProcesses.run("agent-a", new ProcessBuilder("sh", "-c", "echo one; echo two; exit 3"),
            Duration.ofSeconds(10), lines::add);
        assertThat(out.finished()).isTrue();
        assertThat(out.exitCode()).isEqualTo(3);
        assertThat(out.cancelled()).isFalse();
        assertThat(lines).containsExactly("one", "two");
    }

    @Test
    void theTimeLimitStopsARunThatKeepsTalking() throws Exception {
        long t0 = System.nanoTime();
        var out = TrainingProcesses.run("agent-b",
            new ProcessBuilder("sh", "-c", "while true; do echo tick; sleep 0.1; done"),
            Duration.ofMillis(800), l -> { });
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(out.finished()).isFalse();
        assertThat(out.ok()).isFalse();
        assertThat(ms).isLessThan(8_000);
    }

    @Test
    void aCancelStopsTheRunAndWhatItStarted() throws Exception {
        TrainingProcesses.begin("agent-c");
        var pids = new ArrayList<String>();
        var run = CompletableFuture.supplyAsync(() -> {
            try {
                return TrainingProcesses.run("agent-c",
                    new ProcessBuilder("sh", "-c", "sleep 60 & echo $!; wait"), Duration.ofMinutes(5), pids::add);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        for (int i = 0; i < 100 && pids.isEmpty(); i++) Thread.sleep(50);
        assertThat(pids).isNotEmpty();
        assertThat(TrainingProcesses.cancel("agent-c")).isTrue();
        var out = run.get(10, TimeUnit.SECONDS);
        assertThat(out.cancelled()).isTrue();
        assertThat(out.ok()).isFalse();
        var child = ProcessHandle.of(Long.parseLong(pids.getFirst().trim()));
        for (int i = 0; i < 50 && child.map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(50);
        assertThat(child.map(ProcessHandle::isAlive).orElse(false)).as("the child the trainer started").isFalse();
    }

    @Test
    void aCancelBeforeTheRunMeansItNeverStartsUntilTheNextCycle() throws Exception {
        TrainingProcesses.begin("agent-d");
        TrainingProcesses.cancel("agent-d");
        var out = TrainingProcesses.run("agent-d", new ProcessBuilder("true"), Duration.ofSeconds(5), l -> { });
        assertThat(out.cancelled()).isTrue();
        assertThat(out.finished()).isFalse();
        TrainingProcesses.begin("agent-d");
        assertThat(TrainingProcesses.run("agent-d", new ProcessBuilder("true"), Duration.ofSeconds(5), l -> { }).ok())
            .isTrue();
    }

    @Test
    void cancellingOneCompanionLeavesAnothersRunAlone() throws Exception {
        TrainingProcesses.begin("agent-e");
        TrainingProcesses.begin("agent-f");
        TrainingProcesses.cancel("agent-e");
        List<String> none = new ArrayList<>();
        assertThat(TrainingProcesses.run("agent-f", new ProcessBuilder("true"), Duration.ofSeconds(5), none::add).ok())
            .isTrue();
    }
}

package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ChronicleService#buildVoiced} (2026-06-03): the testimony is voiced
 * through the supplied generator; the SYNTHESIS is NEVER voiced (it is the
 * ground-truth half of the divergence detector); and every failure mode degrades
 * to the deterministic chronicle.
 */
class ChronicleServiceVoicedTest {

    @Test
    void voicesTestimony_butNeverSynthesis(@TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:voiced";
        var now = Instant.now();
        Files.write(logFile, List.of(
            tickLine(agent, "Vesna", now.minusSeconds(30), "acted"),
            speakLine(agent, "Vesna", now.minusSeconds(20),
                "I held the weight between us and didn't rush to name it."),
            speakLine(agent, "Vesna", now.minusSeconds(10),
                "There's a steadiness now.")));
        var service = new ChronicleService(new TickLogReader(logFile));

        var sawInput = new AtomicReference<String>();
        Function<String, CompletionStage<String>> voiceFn = det -> {
            sawInput.set(det);
            return CompletableFuture.completedFuture("VOICED:" + det);
        };
        var chron = service.buildVoiced(agent, "Vesna",
            ChronicleService.Scale.DAY, voiceFn).toCompletableFuture().join();

        // Testimony was voiced.
        assertThat(chron.testimony()).startsWith("VOICED:");
        // The voiceFn was handed the TESTIMONY, never the synthesis.
        assertThat(sawInput.get())
            .as("voiceFn must receive the deterministic testimony, not the synthesis")
            .doesNotStartWith("Over the last")          // synthesis lead-in
            .contains("I spoke");                        // testimony lead-in
        // Synthesis is byte-identical to the deterministic build — NOT voiced.
        var deterministic = service.build(agent, "Vesna", ChronicleService.Scale.DAY);
        assertThat(chron.synthesis())
            .as("synthesis must stay deterministic ground truth")
            .isEqualTo(deterministic.synthesis())
            .doesNotStartWith("VOICED:");
    }

    @Test
    void blankVoiceResult_fallsBackToDeterministicTestimony(@TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:blank";
        var now = Instant.now();
        Files.write(logFile, List.of(
            tickLine(agent, "W", now.minusSeconds(20), "acted"),
            speakLine(agent, "W", now.minusSeconds(10), "something")));
        var service = new ChronicleService(new TickLogReader(logFile));

        Function<String, CompletionStage<String>> blank =
            det -> CompletableFuture.completedFuture("   ");
        var chron = service.buildVoiced(agent, "W",
            ChronicleService.Scale.DAY, blank).toCompletableFuture().join();

        var deterministic = service.build(agent, "W", ChronicleService.Scale.DAY);
        assertThat(chron.testimony()).isEqualTo(deterministic.testimony());
    }

    @Test
    void voiceException_fallsBackToDeterministic(@TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:boom";
        var now = Instant.now();
        Files.write(logFile, List.of(
            tickLine(agent, "W", now.minusSeconds(20), "acted"),
            speakLine(agent, "W", now.minusSeconds(10), "something")));
        var service = new ChronicleService(new TickLogReader(logFile));

        Function<String, CompletionStage<String>> boom =
            det -> CompletableFuture.failedFuture(new RuntimeException("voice backend down"));
        var chron = service.buildVoiced(agent, "W",
            ChronicleService.Scale.DAY, boom).toCompletableFuture().join();

        var deterministic = service.build(agent, "W", ChronicleService.Scale.DAY);
        assertThat(chron.testimony()).isEqualTo(deterministic.testimony());
    }

    @Test
    void noTestimony_skipsVoicingEntirely(@TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:silent";
        var now = Instant.now();
        // Ticks only, no speaks / no chosen wants → "(no testimony ...)".
        Files.write(logFile, List.of(
            tickLine(agent, "W", now.minusSeconds(20), "acted"),
            tickLine(agent, "W", now.minusSeconds(10), "chose_rest")));
        var service = new ChronicleService(new TickLogReader(logFile));

        var called = new AtomicBoolean(false);
        Function<String, CompletionStage<String>> voiceFn = det -> {
            called.set(true);
            return CompletableFuture.completedFuture("VOICED:" + det);
        };
        var chron = service.buildVoiced(agent, "W",
            ChronicleService.Scale.DAY, voiceFn).toCompletableFuture().join();

        assertThat(called).as("no point voicing a '(no testimony)' placeholder").isFalse();
        assertThat(chron.testimony()).startsWith("(no testimony");
    }

    @Test
    void nullVoiceFn_returnsDeterministic(@TempDir Path tmp) throws Exception {
        var logFile = tmp.resolve("activity.jsonl");
        var agent = "did:agent:null";
        var now = Instant.now();
        Files.write(logFile, List.of(
            tickLine(agent, "W", now.minusSeconds(20), "acted"),
            speakLine(agent, "W", now.minusSeconds(10), "hello")));
        var service = new ChronicleService(new TickLogReader(logFile));

        var chron = service.buildVoiced(agent, "W",
            ChronicleService.Scale.DAY, null).toCompletableFuture().join();
        var deterministic = service.build(agent, "W", ChronicleService.Scale.DAY);
        assertThat(chron.testimony()).isEqualTo(deterministic.testimony());
    }

    private String tickLine(String agentId, String agentName, Instant ts, String gateOutcome) {
        return "{\"type\":\"tick\","
            + "\"ts\":\"" + ts.toString() + "\","
            + "\"agent\":\"" + agentName + "\","
            + "\"agentId\":\"" + agentId + "\","
            + "\"energy\":0.5,"
            + "\"gateOutcome\":\"" + gateOutcome + "\","
            + "\"nextTickDelaySeconds\":1,"
            + "\"tickDurationMs\":5}";
    }

    private String speakLine(String agentId, String agentName, Instant ts, String text) {
        return "{\"type\":\"speak\","
            + "\"ts\":\"" + ts.toString() + "\","
            + "\"agent\":\"" + agentName + "\","
            + "\"agentId\":\"" + agentId + "\","
            + "\"text\":\"" + text + "\"}";
    }
}

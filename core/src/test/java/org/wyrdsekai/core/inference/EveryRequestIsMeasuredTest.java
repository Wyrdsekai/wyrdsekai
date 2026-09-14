package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inference snapshot the doctor prints, and the p95 the bunshin wall clock is sized
 * from, must be fed by the ordinary dispatch paths — not only by the fallback retry.
 *
 * <p>On the household node the day the counters shipped (2026-09-14): companions talked
 * all morning and {@code /health} said 0 samples, p95 0, one request in flight forever.
 * Only the fallback path stamped a start time; the snapshot was taken before the slot was
 * released.</p>
 */
class EveryRequestIsMeasuredTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static InferenceBackend.NatsRemote.RemoteCaller answering(String text) {
        return (targetZone, sourceZone, request, tokenCallback) -> {
            var msg = new InferenceClient.ChatMessage("assistant", text);
            var choice = new InferenceClient.Choice(0, msg, "stop");
            var usage = new InferenceClient.Usage(3, 2, 5);
            return CompletableFuture.completedFuture(new InferenceClient.ChatResponse(
                "id", "chat.completion", System.currentTimeMillis() / 1000,
                request.model(), List.of(choice), usage));
        };
    }

    @Test
    @DisplayName("a completed chat turn is one latency sample, and the slot shows as free afterwards")
    void aChatTurnIsMeasured() {
        long before = InferenceRouter.snapshot().samples();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "wyrdsekai-3.5-9b", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(answering("hello")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "peer-9b", "llama-server", "nats://peer", List.of("wyrdsekai-3.5-9b"), 5, true));
        sleep(80);

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-measured", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref()));
        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class);
        assertEquals("hello", ok.content());
        sleep(50);

        var snap = InferenceRouter.snapshot();
        assertTrue(snap.samples() >= before + 1,
            "the ordinary dispatch path must record a sample: " + snap.asMap());
        assertEquals(0, snap.inFlight(), "the slot is free once the turn is answered: " + snap.asMap());
        assertEquals(0, snap.queued());
        testKit.stop(router);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

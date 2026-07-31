package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.ReferenceRates;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the NatsRemote metering attribution bug fixed 2026-04-17.
 *
 * <p>Before the fix, {@code InferenceRouter.onInferResult} passed
 * {@code result.backendName()} (e.g. {@code remote-node123-llama-server}) as the
 * {@code providingZone} for {@link MeteringService}. That ID is not a zone — it's
 * an internal backend handle — so bilateral quota bookkeeping was wrong.</p>
 *
 * <p>The fix resolves the backend to its {@code NatsRemote} variant and uses
 * {@code targetZone} instead. This test asserts that invariant end-to-end.</p>
 */
class NatsRemoteMeteringTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void resetMetering() {
        // Singleton — ensure clean slate between tests even if another test suite initialized it.
        MeteringService.init();
        MeteringService.get().clear();
    }

    @Test void metering_attributes_to_targetZone_not_backend_name() {
        // Fake remote caller: returns a ChatResponse with known usage stats.
        var caller = fakeCaller(27, 10);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "wyrdsekai-3.5-9b", null));

        router.tell(new InferenceRouter.SetNatsRemoteCaller(caller));

        // The backend name is deliberately NOT the zone id — this is what the bug
        // was: using this string as providingZone made bilateral metering nonsense.
        var backendName = "remote-nodeXYZ-llama-server";
        router.tell(new InferenceRouter.AddRemoteBackend(
            backendName, "llama-server", "nats://beta",
            List.of("wyrdsekai-3.5-9b"), 110));

        // Allow registration to settle on the actor thread.
        sleep(100);

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-1", "wyrdsekai-3.5-9b",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref(), backendName));

        var resp = probe.expectMessageClass(InferenceRouter.InferOk.class);
        assertThat(resp.promptTokens()).isEqualTo(27);
        assertThat(resp.completionTokens()).isEqualTo(10);

        // The critical assertion: MeteringService got the real zone, not the backend name.
        waitForMetering();

        var events = MeteringService.get().recentEvents(10);
        assertThat(events).hasSize(1);
        var event = events.get(0);
        assertThat(event.providingZone())
            .as("providingZone must be the NATS target zone, not the backend handle")
            .isEqualTo("beta");
        assertThat(event.providingZone())
            .as("providingZone must never be the backend's internal name")
            .isNotEqualTo(backendName);
        assertThat(event.serviceClass()).isEqualTo(ReferenceRates.SERVICE_INFERENCE_SMALL);
        // 27 + 10 tokens = 0.037 small-inference units (tokens/1000).
        assertThat(event.units()).isEqualTo(0.037);
    }

    @Test void large_model_name_heuristic_selects_large_service_class() {
        // Model name contains "70" → treated as large inference for metering.
        var caller = fakeCaller(100, 50);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "llama-70b", null));

        router.tell(new InferenceRouter.SetNatsRemoteCaller(caller));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-big", "llama-server", "nats://gamma",
            List.of("llama-70b"), 110));
        sleep(100);

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-2", "llama-70b",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref(), "remote-big"));
        probe.expectMessageClass(InferenceRouter.InferOk.class);

        waitForMetering();

        var events = MeteringService.get().recentEvents(10);
        assertThat(events.get(0).serviceClass())
            .isEqualTo(ReferenceRates.SERVICE_INFERENCE_LARGE);
        assertThat(events.get(0).providingZone()).isEqualTo("gamma");
    }

    @Test void no_metering_when_no_caller_registered() {
        // If SetNatsRemoteCaller was never sent, AddRemoteBackend for nats:// is
        // skipped entirely — no backend is added, so no metering event fires.
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "any-model", null));

        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-orphan", "llama-server", "nats://delta",
            List.of("any-model"), 110));
        sleep(100);

        var listProbe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(listProbe.ref()));
        var list = listProbe.receiveMessage();
        assertThat(list.backends())
            .as("nats:// backend must be rejected when no caller is registered")
            .isEmpty();
    }

    private static InferenceBackend.NatsRemote.RemoteCaller fakeCaller(
            int promptTokens, int completionTokens) {
        return (targetZone, sourceZone, request, tokenCallback) -> {
            var msg = new InferenceClient.ChatMessage("assistant", "response from " + targetZone);
            var choice = new InferenceClient.Choice(0, msg, "stop");
            var usage = new InferenceClient.Usage(promptTokens, completionTokens,
                promptTokens + completionTokens);
            return CompletableFuture.completedFuture(new InferenceClient.ChatResponse(
                "id-1", "chat.completion", System.currentTimeMillis() / 1000,
                request.model(), List.of(choice), usage));
        };
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** Polling wait — metering records from a whenComplete callback, not the actor thread. */
    private static void waitForMetering() {
        var deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (MeteringService.get().eventCount() > 0) return;
            sleep(25);
        }
        throw new AssertionError("Timed out waiting for MeteringService to record event");
    }
}

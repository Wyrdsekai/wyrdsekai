package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task #36 — a borrowed/remote 9B outage must degrade to local 4B FAST, not
 * stall ~120s per turn.
 *
 * <p>The mechanism: the discovery miss-counter marks a vanished remote DOWN via
 * {@link InferenceRouter.SetBackendHealth}, and the router's periodic health
 * loop deliberately skips {@link InferenceBackend.NatsRemote} so it can't
 * resurrect a peer the discovery loop just buried. Once DOWN,
 * {@code selectBackend} skips the remote and picks the next healthy backend
 * (the local one) <em>without ever dispatching</em> to the dead remote — so
 * there is no timeout wait at all.</p>
 *
 * <p>Both stand-in backends are {@code NatsRemote} so the test can observe
 * exactly which one is dispatched (via a recording caller) without real HTTP —
 * the selection logic under test is backend-type-agnostic.</p>
 */
class InferenceRouterDegradeTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    /** A caller that records whether it was invoked and replies with a marker text. */
    private static InferenceBackend.NatsRemote.RemoteCaller recordingCaller(
            AtomicBoolean invokedFlag, String marker) {
        return (targetZone, sourceZone, request, tokenCallback) -> {
            invokedFlag.set(true);
            var msg = new InferenceClient.ChatMessage("assistant", marker);
            var choice = new InferenceClient.Choice(0, msg, "stop");
            var usage = new InferenceClient.Usage(1, 1, 2);
            return CompletableFuture.completedFuture(new InferenceClient.ChatResponse(
                "id", "chat.completion", System.currentTimeMillis() / 1000,
                request.model(), List.of(choice), usage));
        };
    }

    @Test void dead_remote_marked_down_routes_to_local_without_dispatch() {
        var remoteInvoked = new AtomicBoolean(false);
        var localInvoked = new AtomicBoolean(false);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "wyrdsekai-3.5-9b", null));

        // Preferred remote 9B (priority 2) and a local 4B stand-in (priority 5).
        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(remoteInvoked, "from-remote-9b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-9b", "llama-server", "nats://peer",
            List.of("wyrdsekai-3.5-9b"), 2, true));
        sleep(80);
        // Swap the caller so the "local" backend records into its own flag.
        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(localInvoked, "from-local-4b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "local-4b", "llama-server", "nats://self",
            List.of("wyrdsekai-3.5-4b"), 5, true));
        sleep(80);

        // The borrowed 9B dies — discovery loop marks it DOWN on first miss.
        router.tell(new InferenceRouter.SetBackendHealth("remote-9b", false));
        sleep(80);

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-degrade", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref()));

        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class);
        assertThat(ok.content()).isEqualTo("from-local-4b");
        assertThat(localInvoked.get()).as("local 4B must serve the turn").isTrue();
        assertThat(remoteInvoked.get())
            .as("dead remote must be SKIPPED, never dispatched (no 120s wait)")
            .isFalse();
    }

    @Test void healthy_remote_stays_selected() {
        var remoteInvoked = new AtomicBoolean(false);
        var localInvoked = new AtomicBoolean(false);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "wyrdsekai-3.5-9b", null));

        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(remoteInvoked, "from-remote-9b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-9b", "llama-server", "nats://peer",
            List.of("wyrdsekai-3.5-9b"), 2, true));
        sleep(80);
        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(localInvoked, "from-local-4b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "local-4b", "llama-server", "nats://self",
            List.of("wyrdsekai-3.5-4b"), 5, true));
        sleep(80);

        // No outage — the preferred (priority 2) healthy remote must win.
        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-healthy", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref()));

        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class);
        assertThat(ok.content()).isEqualTo("from-remote-9b");
        assertThat(remoteInvoked.get()).as("healthy borrowed 9B keeps serving").isTrue();
    }

    @Test void remote_remarked_healthy_after_reappear_is_selected_again() {
        var remoteInvoked = new AtomicBoolean(false);

        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "wyrdsekai-3.5-9b", null));

        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(remoteInvoked, "from-remote-9b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-9b", "llama-server", "nats://peer",
            List.of("wyrdsekai-3.5-9b"), 2, true));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(new AtomicBoolean(false), "from-local-4b")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "local-4b", "llama-server", "nats://self",
            List.of("wyrdsekai-3.5-4b"), 5, true));
        sleep(80);

        // Outage then recovery — no permanent exile.
        router.tell(new InferenceRouter.SetBackendHealth("remote-9b", false));
        sleep(40);
        router.tell(new InferenceRouter.SetBackendHealth("remote-9b", true));
        sleep(40);

        var probe = testKit.<InferenceRouter.InferResponse>createTestProbe();
        router.tell(new InferenceRouter.ChatRequest(
            "req-recover", null,
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            64, 0.0, probe.ref()));

        var ok = probe.expectMessageClass(InferenceRouter.InferOk.class);
        assertThat(ok.content()).isEqualTo("from-remote-9b");
        assertThat(remoteInvoked.get()).as("recovered remote is selectable again").isTrue();
    }

    @Test void set_health_on_unknown_backend_is_ignored() {
        // A stale discovery signal for a backend that isn't configured must not
        // inject a phantom health entry.
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "m", null));
        router.tell(new InferenceRouter.SetBackendHealth("ghost", false));
        sleep(40);

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        assertThat(probe.receiveMessage().backends()).isEmpty();
    }

    @Test void set_health_flips_backend_info_flag() {
        var router = testKit.spawn(InferenceRouter.create(
            List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(
            recordingCaller(new AtomicBoolean(false), "x")));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-9b", "llama-server", "nats://peer", List.of("m"), 2, true));
        sleep(80);

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        assertThat(probe.receiveMessage().backends().getFirst().healthy()).isTrue();

        router.tell(new InferenceRouter.SetBackendHealth("remote-9b", false));
        sleep(80);
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        assertThat(probe.receiveMessage().backends().getFirst().healthy()).isFalse();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

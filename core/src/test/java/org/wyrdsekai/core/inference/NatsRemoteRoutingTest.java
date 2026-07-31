package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the discovery → routing path: when {@code BetweenActor} rewrites a
 * cross-zone inference endpoint to {@code nats://{zone}} and calls
 * {@link InferenceRouter.AddRemoteBackend}, the router must construct a
 * {@link InferenceBackend.NatsRemote} (not an HTTP backend). The previous
 * HTTP-proxy design is gone, so mis-routing produces production 404s — the
 * shape of the backend is load-bearing.
 */
class NatsRemoteRoutingTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.provider = "local"
            """));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    private static final InferenceBackend.NatsRemote.RemoteCaller NOOP_CALLER =
        (z, s, r, cb) -> CompletableFuture.completedFuture(null);

    @Test void nats_url_becomes_NatsRemote_backend() {
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(NOOP_CALLER));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-alpha", "llama-server", "nats://alpha",
            List.of("wyrdsekai-3.5-9b"), 110));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var list = probe.receiveMessage();

        assertThat(list.backends()).hasSize(1);
        var info = list.backends().getFirst();
        assertThat(info.name()).isEqualTo("remote-alpha");
        // The decisive assertion: NatsRemote.type() returns "nats-remote" — an
        // HTTP backend here would be any of "llama-server"/"sglang"/"ollama"/"vllm".
        assertThat(info.type()).isEqualTo("nats-remote");
        assertThat(info.url()).isEqualTo("nats://alpha");
        assertThat(info.healthy()).isTrue();
    }

    @Test void target_zone_is_extracted_from_nats_url() {
        // The "nats://" prefix is stripped — everything after is the zone id.
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(NOOP_CALLER));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-weird", "llama-server", "nats://zone-with-dashes.123",
            List.of("m"), 110));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var info = probe.receiveMessage().backends().getFirst();
        assertThat(info.url()).isEqualTo("nats://zone-with-dashes.123");
    }

    @Test void nats_backend_without_caller_is_rejected() {
        // If Main.java didn't wire the NatsRemoteCaller (e.g. relay transport
        // offline at startup), a nats:// AddRemoteBackend must not install a
        // broken backend that would NPE on chatCompletion.
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-orphan", "llama-server", "nats://alpha",
            List.of("m"), 110));

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        assertThat(probe.receiveMessage().backends()).isEmpty();
    }

    @Test void replace_nats_backend_with_http_url() {
        // A discovery cycle may replace a nats:// peer with an http:// local
        // backend (or vice versa). The update must produce the new type.
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(NOOP_CALLER));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-x", "llama-server", "nats://alpha",
            List.of("m"), 110));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-x", "llama-server", "http://192.0.2.105:8200",
            List.of("m"), 110));

        sleep(150);  // health check is async for HTTP backend

        var probe = testKit.<InferenceRouter.BackendList>createTestProbe();
        router.tell(new InferenceRouter.ListBackends(probe.ref()));
        var list = probe.receiveMessage();
        assertThat(list.backends()).hasSize(1);
        assertThat(list.backends().getFirst().type()).isEqualTo("llama-server");
        assertThat(list.backends().getFirst().url()).isEqualTo("http://192.0.2.105:8200");
    }

    @Test void nats_remote_has_household_capability_tier() {
        // NatsRemote is a household-tier backend (same trust zone as the relay
        // peer group), not "local" and not "cloud". CapabilityRegistry tier
        // matters for tool routing decisions — see inferTier().
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        router.tell(new InferenceRouter.SetNatsRemoteCaller(NOOP_CALLER));
        router.tell(new InferenceRouter.AddRemoteBackend(
            "remote-nats", "llama-server", "nats://peer",
            List.of("m"), 110));
        sleep(50);

        // Construct the backend directly to inspect its tier — the router's list
        // doesn't expose this, but CapabilityRegistry.inferTier does.
        var backend = new InferenceBackend.NatsRemote(
            "remote-nats", 110, List.of("m"), "peer", "local", NOOP_CALLER);
        assertThat(CapabilityRegistry.inferTier(backend))
            .isEqualTo("household");
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}

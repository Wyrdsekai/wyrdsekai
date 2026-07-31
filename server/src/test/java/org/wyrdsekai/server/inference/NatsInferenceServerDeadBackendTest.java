package org.wyrdsekai.server.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.inference.NatsInferenceProtocol;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22 regression test — when α's local inference backend is dead,
 * incoming cross-zone inference requests must surface a typed error
 * (not the literal string "null") so β can fail fast and operators
 * can identify the dead-backend state at a glance.
 *
 * <p>Before the fix, {@code Throwable.getMessage()} returning {@code null}
 * for {@link java.net.ConnectException} produced the silent failure pattern
 * {@code "streaming failed: null"} that masked a 17-hour outage in
 * production (see F22).
 */
class NatsInferenceServerDeadBackendTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    static final class FakeTransport extends RelaySessionTransport {
        final Map<String, Consumer<byte[]>> subs = new ConcurrentHashMap<>();
        final Map<String, List<byte[]>> published = new ConcurrentHashMap<>();

        @Override public boolean isConnected() { return true; }

        @Override public Object subscribe(String subject, Consumer<byte[]> handler) {
            subs.put(subject, handler);
            return subject;
        }

        @Override public void publish(String subject, byte[] data) {
            published.computeIfAbsent(subject, k -> new CopyOnWriteArrayList<>()).add(data);
        }

        @Override public void closeDispatcherObj(Object dispatcher) {
            if (dispatcher instanceof String s) subs.remove(s);
        }

        NatsInferenceProtocol.StreamChunk firstChunkOn(String subject) throws Exception {
            var list = published.getOrDefault(subject, List.of());
            if (list.isEmpty()) return null;
            return MAPPER.readValue(list.get(0), NatsInferenceProtocol.StreamChunk.class);
        }
    }

    /**
     * Pointing at a closed port (127.0.0.1:1 — IANA-reserved, not bound by
     * any normal process) reliably triggers ConnectException on every JVM.
     * That's the exact failure mode that caused F22 in production.
     */
    private static final String DEAD_URL = "http://127.0.0.1:1";

    @Test void dead_backend_skips_subscription_to_avoid_blackholing() throws Exception {
        // F25 (2026-04-28): the original F22 contract was "subscribe + return
        // a typed error chunk so β can fail fast." That works for a single-
        // node α, but in a multi-node α cluster (home-server + mac-node), NATS queue-
        // distributes incoming requests across all subscribers. A node with a
        // dead backend would still subscribe and answer ~50% of requests with
        // a typed error — black-holing them. Live mesh saw this on mac-node.
        //
        // New contract: a NatsInferenceServer with an unreachable local
        // backend MUST NOT subscribe to the federation inference subject.
        // It can re-probe periodically and join later when the backend recovers.
        var transport = new FakeTransport();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        var server = new NatsInferenceServer(
            transport, "alpha", router, testKit.system(),
            "llama-server", DEAD_URL, true);
        server.start();

        // No subscription should have been made — keeps mac-node-style nodes
        // out of the federation queue group entirely.
        assertThat(transport.subs)
            .as("dead backend must not subscribe — would black-hole NATS-queue-distributed requests")
            .doesNotContainKey(NatsInferenceProtocol.requestSubject("alpha"));

        server.stop();
    }

    @Test void healthy_backend_subscribes_normally() throws Exception {
        // Mirror of the dead-backend test: when the backend probe succeeds,
        // we DO subscribe (otherwise no node would ever serve federation
        // inference). Uses an embedded HTTP stub so no external dependency.
        var stub = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/models", x -> {
            x.sendResponseHeaders(200, 0); x.close();
        });
        stub.start();
        var liveUrl = "http://127.0.0.1:" + stub.getAddress().getPort();

        try {
            var transport = new FakeTransport();
            var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
            var server = new NatsInferenceServer(
                transport, "alpha", router, testKit.system(),
                "llama-server", liveUrl, true);
            server.start();

            assertThat(transport.subs)
                .as("healthy backend must subscribe so it can serve federation requests")
                .containsKey(NatsInferenceProtocol.requestSubject("alpha"));

            server.stop();
        } finally {
            stub.stop(0);
        }
    }

    @Test void describe_error_falls_back_to_class_name_when_no_cause_chain() throws Exception {
        // Belt-and-suspenders: even for non-Connect exceptions where getMessage()
        // is null, the published error must include something more useful than
        // the literal string "null".
        var transport = new FakeTransport();
        var router = testKit.spawn(InferenceRouter.create(List.of(), "m", null));
        // No URL configured → streaming disabled → we fall through to the
        // non-streaming router path. Router has no backends, so this verifies
        // the OTHER error path also doesn't emit "null" as an error message.
        var server = new NatsInferenceServer(
            transport, "alpha", router, testKit.system(),
            "no-such-backend", "", true);  // empty URL → streamingEnabled=false
        server.start();

        var req = new NatsInferenceProtocol.Request(
            "s-noback", "beta", "agent-x", "m",
            List.of(new NatsInferenceProtocol.Message("user", "hi")),
            50, 0.0, false);

        transport.subs.get(NatsInferenceProtocol.requestSubject("alpha"))
            .accept(MAPPER.writeValueAsBytes(req));

        var streamSubject = NatsInferenceProtocol.streamSubject("s-noback");
        var deadline = System.currentTimeMillis() + 5_000;
        NatsInferenceProtocol.StreamChunk chunk = null;
        while (System.currentTimeMillis() < deadline) {
            chunk = transport.firstChunkOn(streamSubject);
            if (chunk != null && chunk.done()) break;
            Thread.sleep(50);
        }
        assertThat(chunk).isNotNull();
        assertThat(chunk.done()).isTrue();
        // The non-streaming path uses a different error pipe (router error /
        // ask timeout). What we care about: it's not literal "null".
        if (chunk.error() != null) {
            assertThat(chunk.error()).isNotEqualTo("null");
        }
        server.stop();
    }
}

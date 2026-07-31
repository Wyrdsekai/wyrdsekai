package org.wyrdsekai.e2e.tier3;

import org.wyrdsekai.e2e.infra.EmbeddedNatsRelay;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Confirms the embedded nats-server fixture boots, accepts connections, and
 * delivers a round-trip publish/subscribe. If this test is green the
 * higher-level cross-zone tests have a working transport substrate.
 */
class EmbeddedNatsRelayTest {

    private static EmbeddedNatsRelay relay;

    @BeforeAll
    static void startRelay() throws Exception {
        relay = new EmbeddedNatsRelay();
        relay.start();
    }

    @AfterAll
    static void stopRelay() {
        if (relay != null) relay.stop();
    }

    @Test void publish_subscribe_roundtrip() throws Exception {
        var opts = new Options.Builder()
            .server(relay.url())
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        try (var conn = Nats.connect(opts)) {
            var received = new AtomicReference<String>();
            var dispatcher = conn.createDispatcher(msg ->
                received.set(new String(msg.getData(), StandardCharsets.UTF_8)));
            dispatcher.subscribe("test.hello");

            conn.publish("test.hello", "world".getBytes(StandardCharsets.UTF_8));
            conn.flush(Duration.ofSeconds(1));

            await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(received.get()).isEqualTo("world"));
        }
    }
}

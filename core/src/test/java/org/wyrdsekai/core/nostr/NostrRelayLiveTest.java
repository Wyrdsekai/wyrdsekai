package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Tag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.nostr.NostrRelayPool.NostrEventListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-relay integration test for the Nostr bridge.
 *
 * <p><b>Off by default.</b> Set {@code WYRDSEKAI_NOSTR_LIVE_TEST=1} to enable.
 * Default skip keeps CI honest — we don't want to spam public relays on every
 * commit, and connectivity flakes shouldn't break the build.
 *
 * <p>What it exercises end-to-end:
 * <ul>
 *   <li>JDK WebSocket → wss://relay.damus.io (real TLS)</li>
 *   <li>Generate throwaway secp256k1 key</li>
 *   <li>Build + sign a kind:1 event (BIP-340 Schnorr)</li>
 *   <li>Publish via {@code NostrRelayPool} → receive OK ack</li>
 *   <li>Subscribe to the same event by id → receive EVENT + EOSE</li>
 * </ul>
 *
 * <p>If the relay rejects the publish (rate-limited, requires PoW, requires
 * payment) the test will fail — that's the intended signal. Failures here
 * tell you "the relay we picked is no longer hospitable to anonymous publish."
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_NOSTR_LIVE_TEST", matches = "1")
@Tag("needs-network")
class NostrRelayLiveTest {

    private static final String RELAY_URL = "wss://relay.damus.io";

    @Test void publish_and_subscribe_round_trip_on_damus() throws Exception {
        var key = NostrKey.generate();
        var event = NostrEvent.buildAndSign(
            key, 1,
            List.of(List.of("t", "wyrdsekai-live-test")),
            "live-test from wyrdsekai NostrRelayPool — " + Instant.now(),
            Instant.now().getEpochSecond());

        try (var pool = new NostrRelayPool(List.of(RELAY_URL))) {
            pool.start();

            // Wait up to 15s for the WebSocket to come up.
            var deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (pool.stateSnapshot().get(RELAY_URL) == NostrRelayPool.ConnState.OPEN) break;
                Thread.sleep(100);
            }
            assertThat(pool.stateSnapshot().get(RELAY_URL))
                .as("connection to " + RELAY_URL)
                .isEqualTo(NostrRelayPool.ConnState.OPEN);

            var publishResult = pool.publish(event);
            assertThat(publishResult.acceptedCount())
                .as("at least one relay accepted (errors: " + publishResult.errors() + ")")
                .isGreaterThanOrEqualTo(1);

            // Now subscribe and wait for our event to come back.
            var receivedEvent = new AtomicReference<NostrEvent>();
            var receivedLatch = new CountDownLatch(1);
            var eoseSeen = new AtomicBoolean(false);
            var listener = new NostrEventListener() {
                @Override public void onEvent(String relay, String subId, NostrEvent ev) {
                    if (ev.id().equals(event.id())) {
                        receivedEvent.set(ev);
                        receivedLatch.countDown();
                    }
                }
                @Override public void onEose(String relay, String subId) {
                    eoseSeen.set(true);
                }
            };
            pool.subscribe("live-test-sub",
                Map.of("ids", List.of(event.id()), "limit", 1),
                listener);

            // Generous timeout — public relays can be slow under load.
            assertThat(receivedLatch.await(15, TimeUnit.SECONDS))
                .as("our event came back via subscribe within 15s").isTrue();
            assertThat(receivedEvent.get().content()).isEqualTo(event.content());
            assertThat(receivedEvent.get().verify()).isTrue();

            pool.unsubscribe("live-test-sub");
        }
    }
}

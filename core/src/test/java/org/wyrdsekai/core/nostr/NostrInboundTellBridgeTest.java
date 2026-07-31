package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NostrInboundTellBridgeTest {

    static final class Captured {
        String fromId;
        String fromName;
        String toId;
        String message;
    }

    AtomicReference<Captured> last;
    AtomicInteger callCount;
    NostrInboundTellBridge bridge;

    @BeforeEach void setUp() {
        last = new AtomicReference<>();
        callCount = new AtomicInteger();
        bridge = new NostrInboundTellBridge((fromId, fromName, toId, message) -> {
            var c = new Captured();
            c.fromId = fromId;
            c.fromName = fromName;
            c.toId = toId;
            c.message = message;
            last.set(c);
            callCount.incrementAndGet();
            return true;
        });
    }

    @Test void routes_kind1_event_to_registered_agent() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(localKey.pubKeyHex())),
            "hello alice from nostr",
            Instant.now().getEpochSecond());

        var delivered = bridge.handleInbound(event);
        assertThat(delivered).isTrue();
        assertThat(callCount.get()).isEqualTo(1);

        var c = last.get();
        assertThat(c.toId).isEqualTo("agent-alice");
        assertThat(c.message).isEqualTo("hello alice from nostr");
        assertThat(c.fromId).isEqualTo("nostr:" + senderKey.pubKeyHex());
        assertThat(c.fromName).startsWith("npub");
    }

    @Test void ignores_kind0_metadata() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 0,
            List.of(NostrInboundTellBridge.pTag(localKey.pubKeyHex())),
            "{\"name\":\"bob\"}",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isFalse();
        assertThat(callCount.get()).isZero();
    }

    @Test void ignores_when_no_p_tag_matches_registered_pubkey() {
        var senderKey = freshKey();
        var unrelatedKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(unrelatedKey.pubKeyHex())),
            "this is not for alice",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isFalse();
        assertThat(callCount.get()).isZero();
    }

    @Test void ignores_event_without_p_tag() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(),
            "public note, untargeted",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isFalse();
    }

    @Test void unregister_drops_routing() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");
        bridge.unregister(localKey.pubKeyHex());

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(localKey.pubKeyHex())),
            "after unregister",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isFalse();
        assertThat(callCount.get()).isZero();
    }

    @Test void p_tag_lookup_is_case_insensitive() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex().toUpperCase(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(localKey.pubKeyHex().toLowerCase())),
            "should still route",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isTrue();
    }

    @Test void registration_rejects_blanks() {
        assertThatThrownBy(() -> bridge.register(null, "agent"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.register("abc", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void multiple_registrations_route_independently() {
        var senderKey = freshKey();
        var aliceKey = freshKey();
        var bobKey = freshKey();
        bridge.register(aliceKey.pubKeyHex(), "agent-alice");
        bridge.register(bobKey.pubKeyHex(), "agent-bob");

        var toAlice = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(aliceKey.pubKeyHex())),
            "hey alice", Instant.now().getEpochSecond());
        var toBob = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(bobKey.pubKeyHex())),
            "hey bob", Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(toAlice)).isTrue();
        var first = last.get();
        assertThat(first.toId).isEqualTo("agent-alice");
        assertThat(first.message).isEqualTo("hey alice");

        assertThat(bridge.handleInbound(toBob)).isTrue();
        var second = last.get();
        assertThat(second.toId).isEqualTo("agent-bob");
        assertThat(second.message).isEqualTo("hey bob");
    }

    @Test void null_event_is_noop() {
        assertThat(bridge.handleInbound(null)).isFalse();
        assertThat(callCount.get()).isZero();
    }

    @Test void empty_content_routes_with_empty_string() {
        var senderKey = freshKey();
        var localKey = freshKey();
        bridge.register(localKey.pubKeyHex(), "agent-alice");

        var event = NostrEvent.buildAndSign(
            senderKey, 1,
            List.of(NostrInboundTellBridge.pTag(localKey.pubKeyHex())),
            "",
            Instant.now().getEpochSecond());

        assertThat(bridge.handleInbound(event)).isTrue();
        assertThat(last.get().message).isEmpty();
    }

    // ───────── helpers ─────────

    private static NostrKey freshKey() {
        var raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return NostrKey.deriveFromEd25519PrivateKey(raw);
    }
}

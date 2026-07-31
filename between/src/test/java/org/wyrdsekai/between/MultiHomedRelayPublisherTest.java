package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.federation.ZoneManifest;
import org.wyrdsekai.core.config.RelayLegConfig;
import org.wyrdsekai.core.config.RelayLegConfig.Visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * live routing of the federation send/recv path.
 */
class MultiHomedRelayPublisherTest {

    /** Records publishes + holds the last subscribe handler; always "connected". */
    static final class RecTransport extends RelaySessionTransport {
        final List<String> published = new ArrayList<>();
        Consumer<byte[]> handler;
        RecTransport() { super(); }
        @Override public boolean isConnected() { return true; }
        @Override public void publish(String subject, byte[] data) { published.add(subject); }
        @Override public Object subscribe(String subject, Consumer<byte[]> h) { this.handler = h; return new Subscription(subject); }
    }

    private static MultiHomedRelayPublisher.Leg leg(String url, Visibility v, RecTransport t) {
        return new MultiHomedRelayPublisher.Leg(new RelayLegConfig(url, null, null, null, v), t);
    }

    @Test
    void targetZoneParse() {
        assertEquals("windows-node", MultiHomedRelayPublisher.targetZoneFromSubject("federation.windows-node.tell"));
        assertEquals("zfp", MultiHomedRelayPublisher.targetZoneFromSubject("federation.zfp.lbl.tell"));
        assertTrue(MultiHomedRelayPublisher.targetZoneFromSubject("other.x.y") == null);
    }

    @Test
    void noAdverts_broadcastsOverPrivateLegsOnly() {
        var relayNode = new RecTransport();
        var relayB = new RecTransport();
        var pub = new RecTransport(); // public leg — must NOT receive federation egress
        var router = new MultiHomedRelayPublisher(List.of(
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE, relayNode),
            leg("wss://relay-b.example.com:4443", Visibility.PRIVATE, relayB),
            leg("wss://wyrdsekai.org:4443", Visibility.PUBLIC, pub)),
            Visibility.PRIVATE, z -> null);

        router.publish("federation.windows-node.tell", "hi".getBytes());

        assertEquals(1, relayNode.published.size());
        assertEquals(1, relayB.published.size());
        assertEquals(0, pub.published.size(), "private-floor zone must not egress federation over a public leg");
    }

    @Test
    void withAdverts_picksOnlySharedRelay() {
        var relayNode = new RecTransport();
        var relayB = new RecTransport();
        // home-server {relay-node, relayB}; windows-node advertises {relay-node, wyrdsekai.org} → shared = relay-node only.
        var peer = Map.of("windows-node", List.of(
            new ZoneManifest.RelayAdvert("nats://relay-node.lan:4222", null, "private"),
            new ZoneManifest.RelayAdvert("wss://wyrdsekai.org:4443", null, "public")));
        var router = new MultiHomedRelayPublisher(List.of(
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE, relayNode),
            leg("wss://relay-b.example.com:4443", Visibility.PRIVATE, relayB)),
            Visibility.PRIVATE, peer::get);

        router.publish("federation.windows-node.tell", "hi".getBytes());

        assertEquals(1, relayNode.published.size(), "send only over the shared relay");
        assertEquals(0, relayB.published.size(), "no redundant publish over the non-shared private leg");
    }

    @Test
    void noSharedRelay_drops() {
        var relayB = new RecTransport();
        var peer = Map.of("windows-node", List.of(
            new ZoneManifest.RelayAdvert("wss://wyrdsekai.org:4443", null, "public")));
        var router = new MultiHomedRelayPublisher(List.of(
            leg("wss://relay-b.example.com:4443", Visibility.PRIVATE, relayB)),
            Visibility.PRIVATE, peer::get);

        router.publish("federation.windows-node.tell", "hi".getBytes());
        assertEquals(0, relayB.published.size(), "no shared relay → drop, never silently reroute");
    }

    @Test
    void subscribeAll_fansOutAndDedups() {
        var relayNode = new RecTransport();
        var relayB = new RecTransport();
        var router = new MultiHomedRelayPublisher(List.of(
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE, relayNode),
            leg("wss://relay-b.example.com:4443", Visibility.PRIVATE, relayB)),
            Visibility.PRIVATE, z -> null);

        var fired = new AtomicInteger();
        router.subscribeAll("federation.home-server.tell", d -> fired.incrementAndGet());

        // Same message arrives over BOTH legs → handler fires once.
        relayNode.handler.accept("dup".getBytes());
        relayB.handler.accept("dup".getBytes());
        assertEquals(1, fired.get(), "duplicate inbound across legs handled once");

        // A distinct message fires again.
        relayNode.handler.accept("other".getBytes());
        assertEquals(2, fired.get());
    }
}

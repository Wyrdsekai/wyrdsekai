package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.federation.ZoneManifest;
import org.wyrdsekai.core.config.RelayLegConfig;
import org.wyrdsekai.core.config.RelayLegConfig.Visibility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * path selection + privacy rail.
 */
class RelayPathSelectorTest {

    private static RelayLegConfig leg(String url, Visibility v) {
        return new RelayLegConfig(url, null, null, null, v);
    }
    private static ZoneManifest.RelayAdvert adv(String url, String vis) {
        return new ZoneManifest.RelayAdvert(url, null, vis);
    }

    @Test
    void lainWinblowPickRaven() {
        // home-server {relay-node, relayB} ∩ windows-node {relay-node, wyrdsekai.org} = {relay-node}.
        var myLegs = List.of(
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE),
            leg("wss://relay-b.example.com:4443", Visibility.PRIVATE));
        var peer = List.of(
            adv("nats://relay-node.lan:4222", "private"),
            adv("wss://wyrdsekai.org:4443", "public"));
        var choice = RelayPathSelector.pick(myLegs, peer, null, Visibility.PRIVATE);
        assertTrue(choice.isPresent());
        assertEquals("nats://relay-node.lan:4222", choice.get().url());
    }

    @Test
    void noSharedRelay_denies() {
        var myLegs = List.of(leg("nats://relay-node.lan:4222", Visibility.PRIVATE));
        var peer = List.of(adv("wss://wyrdsekai.org:4443", "public"));
        assertTrue(RelayPathSelector.pick(myLegs, peer, null, Visibility.PRIVATE).isEmpty(),
            "no shared relay → deny, never fall back");
    }

    @Test
    void privateFloorNeverEgressesPublic() {
        // Both share a public relay, but a private-floor zone must not egress over it.
        var myLegs = List.of(
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE),
            leg("wss://wyrdsekai.org:4443", Visibility.PUBLIC));
        var peer = List.of(adv("wss://wyrdsekai.org:4443", "public"));
        // Only the public relay is shared → a private-floor zone denies (R1).
        assertTrue(RelayPathSelector.pick(myLegs, peer, null, Visibility.PRIVATE).isEmpty());
        // A public-floor (airlock) zone may use it.
        assertTrue(RelayPathSelector.pick(myLegs, peer, null, Visibility.PUBLIC).isPresent());
    }

    @Test
    void costMetric_prefersPrivateThenLan() {
        // Two shared relays: a public VPS and a private LAN box → pick the LAN private one.
        var myLegs = List.of(
            leg("wss://relay.example.com:4443", Visibility.PUBLIC),
            leg("nats://relay-node.lan:4222", Visibility.PRIVATE));
        var peer = List.of(
            adv("wss://relay.example.com:4443", "public"),
            adv("nats://relay-node.lan:4222", "private"));
        var choice = RelayPathSelector.pick(myLegs, peer, null, Visibility.PUBLIC);
        assertTrue(choice.isPresent());
        assertEquals("nats://relay-node.lan:4222", choice.get().url());
    }

    @Test
    void deterministicTiebreak_bothPeersAgree() {
        // Two equally-ranked private LAN relays → lexicographic tiebreak.
        var myLegs = List.of(
            leg("nats://b.lan:4222", Visibility.PRIVATE),
            leg("nats://a.lan:4222", Visibility.PRIVATE));
        var peer = List.of(adv("nats://a.lan:4222", "private"), adv("nats://b.lan:4222", "private"));
        var choice = RelayPathSelector.pick(myLegs, peer, null, Visibility.PRIVATE);
        assertTrue(choice.isPresent());
        assertEquals("nats://a.lan:4222", choice.get().url(), "lexicographic url tiebreak");
    }

    @Test
    void preMultihomingPeer_fallsBackToNatsUrl() {
        // Peer advertises no relays (old code) → reachable only on its natsUrl.
        var myLegs = List.of(leg("nats://relay-node.lan:4222", Visibility.PRIVATE));
        var choice = RelayPathSelector.pick(myLegs, List.of(), "nats://relay-node.lan:4222", Visibility.PRIVATE);
        assertTrue(choice.isPresent());
        assertEquals("nats://relay-node.lan:4222", choice.get().url());
    }

    @Test
    void normMatchesAcrossSchemeAndPath() {
        assertEquals(RelayPathSelector.norm("nats://relay-node.lan:4222"),
                     RelayPathSelector.norm("relay-node.lan:4222"));
        assertEquals(RelayPathSelector.norm("wss://user@host.com:9/x"),
                     RelayPathSelector.norm("host.com:9"));
    }

    @Test
    void isLanAddress_classifies() {
        assertTrue(RelayPathSelector.isLanAddress("nats://relay-node.lan:4222"));
        assertTrue(RelayPathSelector.isLanAddress("nats://10.0.7.5:4222"));
        assertTrue(RelayPathSelector.isLanAddress("nats://192.168.2.9:4222"));
        assertTrue(RelayPathSelector.isLanAddress("nats://172.16.0.1:4222"));
        // RFC 5737 documentation ranges are PUBLIC-shaped, not RFC 1918 —
        // the original assertions here mistook doc-example IPs for LAN.
        assertFalse(RelayPathSelector.isLanAddress("nats://198.51.100.5:4222"));
        assertFalse(RelayPathSelector.isLanAddress("nats://192.0.2.9:4222"));
        assertTrue(RelayPathSelector.isLanAddress("127.0.0.1:4222"));
        assertFalse(RelayPathSelector.isLanAddress("wss://wyrdsekai.org:4443"));
        assertFalse(RelayPathSelector.isLanAddress("nats://172.50.0.1:4222")); // outside RFC1918
    }
}

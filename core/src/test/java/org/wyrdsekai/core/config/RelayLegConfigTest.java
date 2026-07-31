package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * config foundation.
 *
 * <p>Exercises {@link WyrdConfig#relayLegs()} / {@link WyrdConfig#zonePrivacyFloor()}
 * via the package-private profile-map factory. NOTE: env vars override the
 * profile, so these tests assume the ambient environment has no
 * {@code WYRDSEKAI_RELAY_*} / {@code WYRDSEKAI_ZONE_*} set (true in CI/local).</p>
 */
class RelayLegConfigTest {

    private static WyrdConfig cfg(Map<String, String> profile) {
        return WyrdConfig.forProfile(profile);
    }

    private static Map<String, String> map(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void noRelayConfigured_yieldsEmpty() {
        assertTrue(cfg(map()).relayLegs().isEmpty());
    }

    @Test
    void legacySingleLeg_behavesAsBefore() {
        var c = cfg(map(
            "relay.url", "nats://relay-node.lan:4222",
            "relay.user", "hh-home-server",
            "relay.token", "tok0"));
        var legs = c.relayLegs();
        assertEquals(1, legs.size());
        var leg = legs.get(0);
        assertEquals("nats://relay-node.lan:4222", leg.url());
        assertEquals("hh-home-server", leg.user());
        assertEquals("tok0", leg.token());
        // Default visibility is PRIVATE when unspecified.
        assertTrue(leg.isPrivate());
    }

    @Test
    void twoLegs_numberedSuffix() {
        var c = cfg(map(
            "relay.url", "nats://relay-node.lan:4222",
            "relay.visibility", "private",
            "relay.url_2", "wss://relay.example.com:4443",
            "relay.token_2", "tok2",
            "relay.visibility_2", "private"));
        var legs = c.relayLegs();
        assertEquals(2, legs.size());
        assertEquals("nats://relay-node.lan:4222", legs.get(0).url());
        assertEquals("wss://relay.example.com:4443", legs.get(1).url());
        assertEquals("tok2", legs.get(1).token());
        assertTrue(legs.stream().allMatch(RelayLegConfig::isPrivate));
    }

    @Test
    void numberedLegs_stopAtFirstGap() {
        // url_2 present, url_3 absent, url_4 present → only legs 0 and 2 read.
        var c = cfg(map(
            "relay.url", "nats://a:4222",
            "relay.url_2", "nats://b:4222",
            "relay.url_4", "nats://d:4222"));
        var legs = c.relayLegs();
        assertEquals(2, legs.size());
        assertEquals("nats://a:4222", legs.get(0).url());
        assertEquals("nats://b:4222", legs.get(1).url());
    }

    @Test
    void publicLeg_droppedUnderPrivateFloor() {
        // The load-bearing invariant (§2.1): a private zone must not home public.
        var c = cfg(map(
            "zone.privacy_floor", "private",
            "relay.url", "nats://relay-node.lan:4222",
            "relay.visibility", "private",
            "relay.url_2", "wss://wyrdsekai.org:4443",
            "relay.visibility_2", "public"));
        var legs = c.relayLegs();
        assertEquals(1, legs.size(), "public leg must be dropped under private floor");
        assertEquals("nats://relay-node.lan:4222", legs.get(0).url());
    }

    @Test
    void publicLeg_keptWhenFloorPublic() {
        var c = cfg(map(
            "zone.privacy_floor", "public",
            "relay.url", "nats://relay-node.lan:4222",
            "relay.visibility", "private",
            "relay.url_2", "wss://wyrdsekai.org:4443",
            "relay.visibility_2", "public"));
        var legs = c.relayLegs();
        assertEquals(2, legs.size(), "airlock/commons zone may hold a public leg");
        assertTrue(legs.get(1).isPublic());
    }

    @Test
    void publicLeg_keptWhenExplicitlyAllowed() {
        var c = cfg(map(
            "zone.privacy_floor", "private",
            "zone.allow_public_leg", "true",
            "relay.url", "wss://wyrdsekai.org:4443",
            "relay.visibility", "public"));
        var legs = c.relayLegs();
        assertEquals(1, legs.size());
        assertTrue(legs.get(0).isPublic());
    }

    @Test
    void privacyFloor_defaultsPrivate() {
        assertEquals(RelayLegConfig.Visibility.PRIVATE, cfg(map()).zonePrivacyFloor());
        assertEquals(RelayLegConfig.Visibility.PUBLIC,
            cfg(map("zone.privacy_floor", "public")).zonePrivacyFloor());
    }

    @Test
    void visibilityParse_isLenient() {
        var d = RelayLegConfig.Visibility.PRIVATE;
        assertEquals(RelayLegConfig.Visibility.PUBLIC, RelayLegConfig.Visibility.parse("PUBLIC", d));
        assertEquals(RelayLegConfig.Visibility.PUBLIC, RelayLegConfig.Visibility.parse("commons", d));
        assertEquals(RelayLegConfig.Visibility.PRIVATE, RelayLegConfig.Visibility.parse("hidden", d));
        assertEquals(d, RelayLegConfig.Visibility.parse("nonsense", d));
        assertEquals(d, RelayLegConfig.Visibility.parse(null, d));
        assertEquals(d, RelayLegConfig.Visibility.parse("  ", d));
    }

    @Test
    void blankFieldsBecomeNull() {
        var c = cfg(map(
            "relay.url", "nats://relay-node.lan:4222",
            "relay.user", "",
            "relay.token", "  "));
        var leg = c.relayLegs().get(0);
        assertNull(leg.user());
        assertNull(leg.token());
    }

    @Test
    void recordRejectsNullUrlOrVisibility() {
        boolean threw = false;
        try {
            new RelayLegConfig(null, null, null, null, RelayLegConfig.Visibility.PRIVATE);
        } catch (NullPointerException e) { threw = true; }
        assertTrue(threw, "url must be non-null");
        assertFalse(new RelayLegConfig("nats://x", null, null, null,
            RelayLegConfig.Visibility.PRIVATE).isPublic());
    }
}

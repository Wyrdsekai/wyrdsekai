package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** §4.40 maps, location, geocoding adapter contract tests. */
class MapsAdaptersTest {

    @BeforeEach
    void setup() { CredentialResolver.get().resetForTests(); }
    @AfterEach
    void teardown() { CredentialResolver.get().resetForTests(); }

    private AdapterRequest req(String ns, String method, Map<String, Object> args) {
        return new AdapterRequest(ns, method, args, ItemCapabilitySet.UNRESTRICTED, null);
    }

    @Test
    void google_maps_credential_slot_matches_spec() {
        // §4.40 spec — Google Maps uses {googlemaps.api_key} slot
        // even though the user-facing namespace is `maps.*`.
        var ad = new GoogleMapsAdapter();
        assertEquals("maps", ad.namespace());
        assertEquals("googlemaps.api_key", ad.credentialSlot());
    }

    @Test
    void google_maps_declares_four_methods() {
        var caps = new GoogleMapsAdapter().capabilities();
        assertEquals(4, caps.size());
        assertTrue(caps.contains("geocode"));
        assertTrue(caps.contains("reverse_geocode"));
        assertTrue(caps.contains("directions"));
        assertTrue(caps.contains("places"));
    }

    @Test
    void google_maps_without_key_returns_credential_missing() {
        var resp = new GoogleMapsAdapter().invoke(req("maps", "geocode",
            Map.of("address", "1600 Pennsylvania Ave")));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void nominatim_has_no_credential_slot() {
        assertEquals("", new OSMNominatimAdapter().credentialSlot());
    }

    @Test
    void nominatim_geocode_without_query_returns_missing_arg() {
        var resp = new OSMNominatimAdapter().invoke(req("nominatim", "geocode", Map.of()));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void nominatim_reverse_geocode_without_coords_returns_missing_arg() {
        var resp = new OSMNominatimAdapter().invoke(
            req("nominatim", "reverse_geocode", Map.of("lat", 40.0)));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    @Tag("external")
    void nominatim_geocode_against_live_endpoint() {
        // Network test — tagged "external" so CI can opt in/out.
        var resp = new OSMNominatimAdapter().invoke(
            req("nominatim", "geocode", Map.of("q", "Eiffel Tower")));
        // Either success (200 + body) or a transport_error envelope —
        // both are valid contract outcomes.
        if (resp.success()) {
            assertNotNull(resp.data());
        } else {
            assertNotNull(resp.error().code());
        }
    }

    @Test
    void mapbox_declares_three_methods() {
        var caps = new MapboxAdapter().capabilities();
        assertTrue(caps.contains("geocode"));
        assertTrue(caps.contains("directions"));
        assertTrue(caps.contains("isochrone"));
    }

    @Test
    void mapbox_without_token_returns_credential_missing() {
        var resp = new MapboxAdapter().invoke(req("mapbox", "geocode",
            Map.of("query", "berlin")));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void timezone_declares_lookup_methods() {
        var caps = new TimezoneAdapter().capabilities();
        assertTrue(caps.contains("lookup_by_coords"));
        assertTrue(caps.contains("lookup_by_ip"));
    }

    @Test
    void timezone_with_key_returns_stub() {
        CredentialResolver.get().setSafeReader(slot ->
            "timezone.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
        var resp = new TimezoneAdapter().invoke(
            req("timezone", "lookup_by_coords", Map.of("lat", 40.0, "lon", -74.0)));
        assertEquals("not_yet_wired", resp.error().code());
    }
}

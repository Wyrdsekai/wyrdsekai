package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AmadeusAdapterTest {

    private AmadeusAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new AmadeusAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_amadeus() {
        assertEquals("amadeus", adapter.namespace());
    }

    @Test
    void declares_three_read_only_caps() {
        assertEquals(3, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("flight_search"));
        assertTrue(adapter.capabilities().contains("hotel_search"));
        assertTrue(adapter.capabilities().contains("car_search"));
    }

    @Test
    void credential_slot_is_client_id() {
        assertEquals("amadeus.client_id", adapter.credentialSlot());
    }

    @Test
    void flight_search_missing_args_returns_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "flight_search",
            Map.of("origin", "JFK"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void flight_search_no_cred_returns_stub_results() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "flight_search",
            Map.of("origin", "JFK", "destination", "NRT", "date", "2026-06-01"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
        assertEquals("credential_missing:amadeus.client_id", data.get("reason"));
    }

    @Test
    void hotel_search_no_cred_returns_stub() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "hotel_search",
            Map.of("city", "Paris"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void car_search_no_cred_returns_stub() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "car_search",
            Map.of("city", "Tokyo"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "delete_universe",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void hotel_search_blank_city_returns_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("amadeus", "hotel_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void with_credential_returns_live_not_wired_marker() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("test-id"));
        var resp = adapter.invoke(new AdapterRequest("amadeus", "flight_search",
            Map.of("origin", "JFK", "destination", "NRT", "date", "2026-06-01"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("live_not_wired", data.get("reason"));
    }
}

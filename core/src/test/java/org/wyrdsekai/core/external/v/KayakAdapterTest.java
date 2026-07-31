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

class KayakAdapterTest {

    private KayakAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new KayakAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_and_caps() {
        assertEquals("kayak", adapter.namespace());
        assertTrue(adapter.capabilities().contains("flight_search"));
        assertTrue(adapter.capabilities().contains("hotel_search"));
    }

    @Test
    void credential_slot() {
        assertEquals("kayak.api_key", adapter.credentialSlot());
    }

    @Test
    void flight_search_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("kayak", "flight_search",
            Map.of("origin", "SFO", "destination", "LAX"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void flight_search_blank_returns_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("kayak", "flight_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void hotel_search_with_cred_returns_live_not_wired() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("test-key"));
        var resp = adapter.invoke(new AdapterRequest("kayak", "hotel_search",
            Map.of("city", "Boston"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("live_not_wired", data.get("reason"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("kayak", "book",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

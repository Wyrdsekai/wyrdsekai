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

class GoogleFlightsAdapterTest {

    private GoogleFlightsAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new GoogleFlightsAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace() { assertEquals("google_flights", adapter.namespace()); }

    @Test
    void single_search_capability() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("search"));
    }

    @Test
    void search_blank_origin_returns_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("google_flights", "search",
            Map.of("destination", "NRT"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void search_no_cred_returns_stub() {
        var resp = adapter.invoke(new AdapterRequest("google_flights", "search",
            Map.of("origin", "SFO", "destination", "NRT"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertTrue(((String) data.get("reason")).startsWith("credential_missing"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("google_flights", "book",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void search_with_cred_returns_live_not_wired() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("key"));
        var resp = adapter.invoke(new AdapterRequest("google_flights", "search",
            Map.of("origin", "SFO", "destination", "NRT"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }
}

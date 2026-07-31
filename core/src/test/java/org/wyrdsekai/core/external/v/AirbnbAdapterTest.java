package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirbnbAdapterTest {

    private AirbnbAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new AirbnbAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_airbnb() { assertEquals("airbnb", adapter.namespace()); }

    @Test
    void only_listing_search() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("listing_search"));
    }

    @Test
    void listing_search_blank_location_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("airbnb", "listing_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void listing_search_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("airbnb", "listing_search",
            Map.of("location", "Lisbon"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("airbnb", "reserve",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }
}

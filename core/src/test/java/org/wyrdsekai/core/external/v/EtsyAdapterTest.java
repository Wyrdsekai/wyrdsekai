package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtsyAdapterTest {

    private EtsyAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new EtsyAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_etsy() { assertEquals("etsy", adapter.namespace()); }

    @Test
    void caps_search_and_lookup() {
        assertTrue(adapter.capabilities().contains("search"));
        assertTrue(adapter.capabilities().contains("listing_lookup"));
    }

    @Test
    void search_blank_query() {
        var resp = adapter.invoke(new AdapterRequest("etsy", "search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void search_no_cred_returns_stub_with_listings_list() {
        var resp = adapter.invoke(new AdapterRequest("etsy", "search",
            Map.of("query", "ceramic mug"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
        assertNotNull(data.get("listings"));
    }

    @Test
    void listing_lookup_blank_id_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("etsy", "listing_lookup",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("etsy", "list_listing",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

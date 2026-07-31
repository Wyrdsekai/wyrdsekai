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

class AmazonAdapterTest {

    private AmazonAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new AmazonAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_amazon() { assertEquals("amazon", adapter.namespace()); }

    @Test
    void search_and_lookup_only_no_purchase() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("search"));
        assertTrue(caps.contains("item_lookup"));
        assertFalse(caps.contains("purchase"), "Tier 7 purchase deferred");
        assertFalse(caps.contains("cart_add"), "Tier 5 cart deferred");
    }

    @Test
    void search_blank_query_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("amazon", "search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void search_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("amazon", "search",
            Map.of("query", "kindle paperwhite"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void item_lookup_blank_asin_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("amazon", "item_lookup",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void item_lookup_with_cred_returns_live_not_wired() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("akiakey"));
        var resp = adapter.invoke(new AdapterRequest("amazon", "item_lookup",
            Map.of("asin", "B0B7PYV7MF"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void unknown_method_purchase_is_unknown() {
        var resp = adapter.invoke(new AdapterRequest("amazon", "purchase",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

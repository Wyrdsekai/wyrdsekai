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

class ZillowAdapterTest {

    private ZillowAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new ZillowAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_zillow() { assertEquals("zillow", adapter.namespace()); }

    @Test
    void caps_property_search_and_value_estimate() {
        assertTrue(adapter.capabilities().contains("property_search"));
        assertTrue(adapter.capabilities().contains("value_estimate"));
    }

    @Test
    void property_search_blank() {
        var resp = adapter.invoke(new AdapterRequest("zillow", "property_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void value_estimate_blank_address() {
        var resp = adapter.invoke(new AdapterRequest("zillow", "value_estimate",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void property_search_no_cred_returns_stub() {
        var resp = adapter.invoke(new AdapterRequest("zillow", "property_search",
            Map.of("query", "Brooklyn 11215"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void value_estimate_with_cred_returns_estimate_shape() {
        CredentialResolver.get().setSafeReader(slot -> Optional.of("zkey"));
        var resp = adapter.invoke(new AdapterRequest("zillow", "value_estimate",
            Map.of("address", "1600 Amphitheatre Parkway"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("estimate"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("zillow", "delete_listing",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }
}

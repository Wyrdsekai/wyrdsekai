package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedfinAdapterTest {

    private RedfinAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new RedfinAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_redfin() { assertEquals("redfin", adapter.namespace()); }

    @Test
    void only_property_search_cap() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("property_search"));
    }

    @Test
    void property_search_blank_query() {
        var resp = adapter.invoke(new AdapterRequest("redfin", "property_search",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void property_search_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("redfin", "property_search",
            Map.of("query", "Seattle 98109"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("redfin", "value_estimate",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }
}

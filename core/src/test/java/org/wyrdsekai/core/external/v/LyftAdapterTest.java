package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LyftAdapterTest {

    private LyftAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new LyftAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_lyft() { assertEquals("lyft", adapter.namespace()); }

    @Test
    void cred_slot_lyft_client_id() {
        assertEquals("lyft.client_id", adapter.credentialSlot());
    }

    @Test
    void estimate_missing_to_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("lyft", "estimate",
            Map.of("from", "Mission"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void estimate_no_cred_stub_products_empty() {
        var resp = adapter.invoke(new AdapterRequest("lyft", "estimate",
            Map.of("from", "Castro", "to", "Mission"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("lyft", "request",
            Map.of("from", "A", "to", "B"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }
}

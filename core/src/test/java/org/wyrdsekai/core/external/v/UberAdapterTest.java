package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UberAdapterTest {

    private UberAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new UberAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_uber() { assertEquals("uber", adapter.namespace()); }

    @Test
    void only_estimate_no_request() {
        assertEquals(1, adapter.capabilities().size());
        assertTrue(adapter.capabilities().contains("estimate"));
        assertFalse(adapter.capabilities().contains("request"),
            "Phase V is read-only — uber.request stays out of scope");
    }

    @Test
    void estimate_blank_from_returns_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("uber", "estimate",
            Map.of("to", "Mission, SF"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void estimate_no_cred_stub() {
        var resp = adapter.invoke(new AdapterRequest("uber", "estimate",
            Map.of("from", "Castro", "to", "Mission"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void request_method_is_unknown_method_in_phase_v() {
        var resp = adapter.invoke(new AdapterRequest("uber", "request",
            Map.of("from", "A", "to", "B"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

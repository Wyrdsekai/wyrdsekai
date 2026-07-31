package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransitlandAdapterTest {

    private TransitlandAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new TransitlandAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void cleanup() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_transit_rt() { assertEquals("transit_rt", adapter.namespace()); }

    @Test
    void caps_routes_stops_schedules() {
        var caps = adapter.capabilities();
        assertEquals(3, caps.size());
        assertTrue(caps.contains("routes"));
        assertTrue(caps.contains("stops"));
        assertTrue(caps.contains("schedules"));
    }

    @Test
    void routes_blank_stop_id_bad_request() {
        var resp = adapter.invoke(new AdapterRequest("transit_rt", "routes",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("bad_request", resp.error().code());
    }

    @Test
    void stops_requires_lat_lon() {
        var resp = adapter.invoke(new AdapterRequest("transit_rt", "stops",
            Map.of("lat", 37.7749),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void routes_returns_stub_data_with_empty_list() {
        var resp = adapter.invoke(new AdapterRequest("transit_rt", "routes",
            Map.of("stop_id", "f-9q8y-19th"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(true, data.get("stub"));
        assertNotNull(data.get("routes"));
    }

    @Test
    void schedules_requires_route_id() {
        var resp = adapter.invoke(new AdapterRequest("transit_rt", "schedules",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
    }

    @Test
    void unknown_method() {
        var resp = adapter.invoke(new AdapterRequest("transit_rt", "delete_route",
            Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}

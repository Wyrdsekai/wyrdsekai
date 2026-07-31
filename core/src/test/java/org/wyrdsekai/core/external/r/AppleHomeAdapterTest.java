package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppleHomeAdapterTest {

    private final AppleHomeAdapter adapter = new AppleHomeAdapter();

    @Test
    void namespace_is_apple_home() {
        assertEquals("apple_home", adapter.namespace());
    }

    @Test
    void capabilities_cover_read_and_write() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("list_accessories"));
        assertTrue(caps.contains("get_state"));
        assertTrue(caps.contains("set_state"));
    }

    @Test
    void list_accessories_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("apple_home", "list_accessories",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void get_state_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("apple_home", "get_state",
            Map.of("accessoryId", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void set_state_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("apple_home", "set_state",
            Map.of("accessoryId", "x", "value", true),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("apple_home", "factory_reset",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}

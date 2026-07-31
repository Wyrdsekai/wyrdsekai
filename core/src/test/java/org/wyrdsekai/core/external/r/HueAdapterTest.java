package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HueAdapterTest {

    private MockHttpFixture mock;
    private HueAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new HueAdapter();
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "hue.bridge_ip" -> Optional.of(mock.baseUrl());
            case "hue.username" -> Optional.of("hue-user");
            default -> Optional.empty();
        });
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void list_lights_uses_username_in_path() {
        mock.onPath("/api/hue-user/lights", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"1\":{\"name\":\"Lamp\",\"state\":{\"on\":true}}}"));
        var resp = adapter.invoke(new AdapterRequest("hue", "list_lights",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("lights"));
    }

    @Test
    void set_state_puts_to_state_endpoint() {
        mock.onPath("/api/hue-user/lights/3/state", (ex, body) -> MockHttpFixture.Reply.json(
            "[{\"success\":{\"/lights/3/state/on\":true}}]"));
        var resp = adapter.invoke(new AdapterRequest("hue", "set_state",
            Map.of("lightId", "3", "state", Map.of("on", true, "bri", 200)),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        var rec = mock.recorded().get(0);
        assertEquals("PUT", rec.method);
        assertTrue(rec.body.contains("\"on\":true"));
    }

    @Test
    void set_state_requires_state_map() {
        var resp = adapter.invoke(new AdapterRequest("hue", "set_state",
            Map.of("lightId", "1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void missing_bridge_ip_fails_credential() {
        CredentialResolver.get().setSafeReader(slot ->
            "hue.username".equals(slot) ? Optional.of("u") : Optional.empty());
        var resp = adapter.invoke(new AdapterRequest("hue", "list_lights",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("hue", "destroy_bridge",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}

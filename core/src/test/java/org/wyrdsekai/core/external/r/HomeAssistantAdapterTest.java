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

class HomeAssistantAdapterTest {

    private MockHttpFixture mock;
    private HomeAssistantAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new HomeAssistantAdapter();
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "hass.url" -> Optional.of(mock.baseUrl());
            case "hass.token" -> Optional.of("ha-tk");
            default -> Optional.empty();
        });
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_hass() {
        assertEquals("hass", adapter.namespace());
        assertEquals("hass.token", adapter.credentialSlot());
    }

    @Test
    void list_entities_returns_states_array() {
        mock.onPath("/api/states", (ex, body) -> MockHttpFixture.Reply.json(
            "[{\"entity_id\":\"light.kitchen\",\"state\":\"on\"}]"));
        var resp = adapter.invoke(new AdapterRequest("hass", "list_entities",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("entities"));
    }

    @Test
    void get_state_returns_state_block() {
        mock.onPath("/api/states/light.kitchen", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"state\":\"on\",\"attributes\":{\"brightness\":255}, \"last_changed\":\"2026-05-05\"}"));
        var resp = adapter.invoke(new AdapterRequest("hass", "get_state",
            Map.of("entityId", "light.kitchen"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("on", data.get("state"));
    }

    @Test
    void call_service_posts_to_correct_path() {
        mock.onPath("/api/services/scene/turn_on", (ex, body) -> MockHttpFixture.Reply.json("[]"));
        var resp = adapter.invoke(new AdapterRequest("hass", "call_service",
            Map.of("domain", "scene", "service", "turn_on",
                "data", Map.of("entity_id", "scene.morning")),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        var rec = mock.recorded().get(0);
        assertEquals("POST", rec.method);
        assertTrue(rec.body.contains("scene.morning"));
    }

    @Test
    void call_service_requires_domain_and_service() {
        var resp = adapter.invoke(new AdapterRequest("hass", "call_service",
            Map.of("domain", "scene"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void missing_url_slot_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(slot ->
            "hass.token".equals(slot) ? Optional.of("t") : Optional.empty());
        var resp = adapter.invoke(new AdapterRequest("hass", "list_entities",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("hass", "summon",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}

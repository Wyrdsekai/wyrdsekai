package org.wyrdsekai.core.external.s;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Phase S — unit tests for the Vonage adapter. */
class VonageAdapterTest {

    private VonageAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new VonageAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_vonage() {
        assertEquals("vonage", adapter.namespace());
    }

    @Test
    void only_send_sms_is_exposed() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("send_sms"));
        assertEquals(1, caps.size());
    }

    @Test
    void missing_api_key_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "vonage", "send_sms",
            Map.of("to", "+15551234567", "text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void missing_secret_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(slot ->
            slot.equals("vonage.api_key") ? Optional.of("abc") : Optional.empty());
        var resp = adapter.invoke(new AdapterRequest(
            "vonage", "send_sms",
            Map.of("to", "+15551234567", "text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertTrue(resp.error().message().contains("vonage.api_secret"));
    }

    @Test
    void send_sms_without_to_returns_invalid_args() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "vonage", "send_sms", Map.of("text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void send_sms_without_from_no_default_returns_invalid_args() {
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "vonage.api_key" -> Optional.of("abc");
            case "vonage.api_secret" -> Optional.of("def");
            default -> Optional.empty();
        });
        var resp = adapter.invoke(new AdapterRequest(
            "vonage", "send_sms", Map.of("to", "+15550001111", "text", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "vonage", "send_voice", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    private void wireCreds() {
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "vonage.api_key" -> Optional.of("abc");
            case "vonage.api_secret" -> Optional.of("def");
            case "vonage.from_number" -> Optional.of("WyrdBot");
            default -> Optional.empty();
        });
    }
}

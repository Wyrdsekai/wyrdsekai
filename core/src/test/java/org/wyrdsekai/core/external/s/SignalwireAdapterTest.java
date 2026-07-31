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

/** Phase S — unit tests for the SignalWire adapter. */
class SignalwireAdapterTest {

    private SignalwireAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new SignalwireAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_signalwire() {
        assertEquals("signalwire", adapter.namespace());
    }

    @Test
    void only_send_sms_is_exposed() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("send_sms"));
        assertEquals(1, caps.size());
    }

    @Test
    void missing_project_id_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "signalwire", "send_sms",
            Map.of("to", "+15551234567", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void missing_token_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(slot ->
            slot.equals("signalwire.project_id") ? Optional.of("proj-xxx") : Optional.empty());
        var resp = adapter.invoke(new AdapterRequest(
            "signalwire", "send_sms",
            Map.of("to", "+15551234567", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertTrue(resp.error().message().contains("signalwire.api_token"));
    }

    @Test
    void missing_space_url_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "signalwire.project_id" -> Optional.of("proj-xxx");
            case "signalwire.api_token" -> Optional.of("tok-xxx");
            default -> Optional.empty();
        });
        var resp = adapter.invoke(new AdapterRequest(
            "signalwire", "send_sms",
            Map.of("to", "+15551234567", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertTrue(resp.error().message().contains("signalwire.space_url"));
    }

    @Test
    void send_sms_without_body_returns_invalid_args() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "signalwire", "send_sms", Map.of("to", "+15550001111"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "signalwire", "voice_call", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    private void wireCreds() {
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "signalwire.project_id" -> Optional.of("proj-xxx");
            case "signalwire.api_token" -> Optional.of("tok-xxx");
            case "signalwire.space_url" -> Optional.of("mycorp.signalwire.com");
            case "signalwire.from_number" -> Optional.of("+15550000000");
            default -> Optional.empty();
        });
    }
}

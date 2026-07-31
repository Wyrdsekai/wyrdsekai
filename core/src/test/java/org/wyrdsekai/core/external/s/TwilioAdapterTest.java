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

/** Phase S — unit tests for the Twilio adapter (offline; no real SMS sent). */
class TwilioAdapterTest {

    private TwilioAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new TwilioAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_twilio() {
        assertEquals("twilio", adapter.namespace());
    }

    @Test
    void capabilities_cover_sms_whatsapp_voice() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("send_sms"));
        assertTrue(caps.contains("send_whatsapp"));
        assertTrue(caps.contains("voice_call"));
        assertEquals(3, caps.size());
    }

    @Test
    void missing_sid_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "send_sms",
            Map.of("to", "+15551234567", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertTrue(resp.error().message().contains("twilio.account_sid"));
    }

    @Test
    void missing_token_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(slot ->
            slot.equals("twilio.account_sid") ? Optional.of("ACxxx") : Optional.empty());
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "send_sms",
            Map.of("to", "+15551234567", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
        assertTrue(resp.error().message().contains("twilio.auth_token"));
    }

    @Test
    void send_sms_without_to_or_body_returns_invalid_args() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "send_sms", Map.of("to", "+15550001111"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void send_sms_without_from_returns_invalid_args_when_no_default() {
        // Wire only sid + token, no from-number default.
        CredentialResolver.get().setSafeReader(slot -> {
            if (slot.equals("twilio.account_sid")) return Optional.of("ACxxx");
            if (slot.equals("twilio.auth_token")) return Optional.of("auth-xxx");
            return Optional.empty();
        });
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "send_sms",
            Map.of("to", "+15550001111", "body", "hi"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void voice_call_requires_twiml_or_url() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "voice_call", Map.of("to", "+15550001111"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        wireCreds();
        var resp = adapter.invoke(new AdapterRequest(
            "twilio", "send_fax", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    private void wireCreds() {
        CredentialResolver.get().setSafeReader(slot -> switch (slot) {
            case "twilio.account_sid" -> Optional.of("ACxxx");
            case "twilio.auth_token" -> Optional.of("auth-xxx");
            case "twilio.from_number" -> Optional.of("+15550000000");
            default -> Optional.empty();
        });
    }
}

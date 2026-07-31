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

/** Phase S — unit tests for the Wise adapter (read-only). */
class WiseAdapterTest {

    private WiseAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new WiseAdapter();
        CredentialResolver.get().resetForTests();
    }

    @AfterEach
    void teardown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_wise() {
        assertEquals("wise", adapter.namespace());
    }

    @Test
    void capabilities_are_read_only() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("balance"));
        assertTrue(caps.contains("recent_transfers"));
        // wise.write methods are deferred — must not appear in Phase S.
        assertFalse(caps.contains("create_transfer"));
        assertFalse(caps.contains("send"));
        assertEquals(2, caps.size());
    }

    @Test
    void credential_slot_is_api_token() {
        assertEquals("wise.api_token", adapter.credentialSlot());
    }

    @Test
    void missing_token_returns_credential_missing() {
        var resp = adapter.invoke(new AdapterRequest(
            "wise", "balance", Map.of("profileId", "12345"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void balance_without_profile_id_returns_invalid_args() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("wise-token"));
        var resp = adapter.invoke(new AdapterRequest(
            "wise", "balance", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void recent_transfers_without_profile_id_returns_invalid_args() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("wise-token"));
        var resp = adapter.invoke(new AdapterRequest(
            "wise", "recent_transfers", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("invalid_args", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        CredentialResolver.get().setSafeReader(s -> Optional.of("wise-token"));
        var resp = adapter.invoke(new AdapterRequest(
            "wise", "send_transfer", Map.of("profileId", "12345"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }
}
